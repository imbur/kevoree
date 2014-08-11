package org.kevoree.core.impl

import org.kevoree.api.handler.UUIDModel
import org.kevoree.ContainerRoot
import java.util.UUID
import org.kevoree.api.handler.UpdateCallback
import org.kevoree.api.handler.ModelListener
import org.kevoree.api.BootstrapService
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import org.kevoree.api.handler.LockCallBack
import org.kevoree.log.Log
import org.kevoree.ContainerNode
import java.util.concurrent.TimeUnit
import org.kevoree.api.NodeType
import org.kevoree.core.impl.deploy.PrimitiveCommandExecutionHelper
import org.kevoree.modeling.api.trace.TraceSequence
import org.kevoree.kevscript.KevScriptEngine
import org.kevoree.api.handler.UpdateContext
import org.kevoree.factory.DefaultKevoreeFactory
import org.kevoree.api.telemetry.TelemetryListener

class PreCommand(context: UpdateContext, modelListeners: KevoreeListeners) {
    var alreadyCall = false
    val preRollbackTest: () -> Boolean = {() ->
        if (!alreadyCall) {
            modelListeners.preRollback(context)
            alreadyCall = true
        }
        true
    }
}

class KevoreeCoreBean : ContextAwareModelService {

    var pending: ContainerRoot? = null

    var telemetryListeners = ArrayList<TelemetryListener>()

    fun addTelemetryListener(l: TelemetryListener) {
        telemetryListeners.add(l)
    }

    override fun getPendingModel(): ContainerRoot? {
        return pending
    }

    val modelListeners = KevoreeListeners()
    var bootstrapService: BootstrapService? = null
    var _nodeName: String = ""
    var nodeInstance: org.kevoree.api.NodeType? = null
    var resolver: MethodAnnotationResolver? = null


    var models: MutableList<UUIDModel> = ArrayList<UUIDModel>()
    val kevoreeFactory = org.kevoree.factory.DefaultKevoreeFactory()
    val model: AtomicReference<UUIDModel> = AtomicReference<UUIDModel>()
    var lastDate: Date = Date(System.currentTimeMillis())
    val modelCloner = kevoreeFactory.createModelCloner()
    //val modelChecker = RootChecker()
    private var scheduler: ExecutorService? = null
    private var lockWatchDog: ScheduledExecutorService? = null
    private var futurWatchDog: ScheduledFuture<out Any?>? = null
    private var currentLock: TupleLockCallBack? = null
    var factory = DefaultKevoreeFactory()

    data class TupleLockCallBack(val uuid: UUID, val callback: LockCallBack)

    override fun getNodeName(): String {
        return _nodeName
    }

    fun setNodeName(nn: String) {
        _nodeName = nn
    }

    inline fun cloneCurrentModel(pmodel: ContainerRoot?): ContainerRoot {
        return modelCloner.clone(pmodel!!, true)!!
    }

    override fun registerModelListener(listener: ModelListener?, callerPath: String?) {
        modelListeners.addListener(listener!!)
    }

    override fun unregisterModelListener(listener: ModelListener?, callerPath: String?) {
        modelListeners.removeListener(listener!!)
    }

    override fun acquireLock(callBack: LockCallBack?, timeout: Long?, callerPath: String?) {
        scheduler?.submit(AcquireLock(callBack!!, timeout!!))
    }

    override fun getCurrentModel(): UUIDModel? {
        return model.get()!!
    }
    override fun compareAndSwap(model: ContainerRoot?, uuid: UUID?, callback: UpdateCallback?, callerPath: String?) {
        scheduler!!.submit(UpdateModelRunnable(cloneCurrentModel(model), uuid, callback, callerPath))
    }
    override fun update(model: ContainerRoot?, callback: UpdateCallback?, callerPath: String?) {
        scheduler!!.submit(UpdateModelRunnable(cloneCurrentModel(model), null, callback, callerPath))
    }

    inner class UpdateModelRunnable(val targetModel: ContainerRoot, val uuid: UUID?, val callback: UpdateCallback?, val callerPath: String?) : Runnable {
        override fun run() {
            var res: Boolean = false
            if (currentLock != null) {
                if (uuid?.compareTo(currentLock!!.uuid) == 0) {
                    res = internal_update_model(targetModel, callerPath)
                } else {
                    Log.debug("Core Locked , bad UUID {}", uuid)
                    res = false //LOCK REFUSED !
                }
            } else {
                //COMMON CHECK
                if (uuid != null) {
                    if (uuid.compareTo(model.get()!!.getUUID()!!) == 0) {
                        res = internal_update_model(targetModel, callerPath)
                    } else {
                        res = false
                    }
                } else {
                    res = internal_update_model(targetModel, callerPath)
                }
            }
            object : Thread() {
                override fun run() {
                    callback?.run(res)
                }
            }.start()
        }
    }

    val scriptEngine = KevScriptEngine()
    inner class UpdateScriptRunnable(val script: String, val callback: UpdateCallback?, val callerPath: String?) : Runnable {
        override fun run() {
            try {
                val newModel = modelCloner.clone(model.get()?.getModel() as ContainerRoot, false) as ContainerRoot
                scriptEngine.execute(script, newModel)
                var res = internal_update_model(cloneCurrentModel(newModel), callerPath)
                object : Thread() {
                    override fun run() {
                        callback?.run(res)
                    }
                }.start()
            } catch(e: Throwable) {
                callback?.run(false)
            }
        }
    }

    override fun submitScript(script: String?, callback: UpdateCallback?, callerPath: String?) {
        if (script != null && currentLock == null) {
            scheduler!!.submit(UpdateScriptRunnable(script, callback, callerPath))
        } else {
            callback?.run(false)
        }
    }

    inner class UpdateSequenceRunnable(val sequence: TraceSequence, val callback: UpdateCallback?, val callerPath: String?) : Runnable {
        override fun run() {
            try {
                val newModel = modelCloner.clone(model.get()?.getModel() as ContainerRoot, false) as ContainerRoot
                sequence.applyOn(newModel)
                var res = internal_update_model(cloneCurrentModel(newModel), callerPath)
                object : Thread() {
                    override fun run() {
                        callback?.run(res)
                    }
                }.start()
            } catch(e: Throwable) {
                Log.error("error while apply trace sequence", e)
                callback?.run(false)
            }
        }
    }

    override fun submitSequence(sequence: TraceSequence?, callback: UpdateCallback?, callerPath: String?) {
        if (sequence != null && currentLock == null) {
            scheduler!!.submit(UpdateSequenceRunnable(sequence, callback, callerPath))
        } else {
            callback?.run(false)
        }
    }

    private fun switchToNewModel(c: ContainerRoot) {
        var cc: ContainerRoot? = c
        if (!c.isReadOnly()) {
            Log.error("It is not safe to store ReadWrite model")
            cc = modelCloner.clone(c, true)
        }
        //current model is backed-up
        val previousModel = model.get()
        if (previousModel != null) {
            models.add(previousModel)
        }
        // TODO : MAGIC NUMBER ;-) , ONLY KEEP 10 PREVIOUS MODEL
        if (models.size > 15) {
            models = models.drop(5) as MutableList<UUIDModel>
            Log.debug("Garbage old previous model")
        }
        //Changes the current model by the new model
        if (cc != null) {
            val uuidModel = UUIDModelImpl(UUID.randomUUID(), cc!!)
            model.set(uuidModel)
            lastDate = Date(System.currentTimeMillis())
            //Fires the update to listeners
            modelListeners.notifyAllListener()
        }
    }

    fun isAnyTelemetryListener(): Boolean {
        return !telemetryListeners.isEmpty()
    }

    fun broadcastTelemetry(typeMessage : String, message : String, stack : String){

        var event = TelemetryEventImpl.build(getNodeName(),typeMessage,message,stack)
        for(tl in telemetryListeners){
            tl.notify(event)
        }
    }

    fun start() {
        if (getNodeName() == "") {
            setNodeName("node0")
        }
        modelListeners.start(getNodeName())
        Log.info("Kevoree Start event : node name = {}", getNodeName())
        scheduler = java.util.concurrent.Executors.newSingleThreadExecutor(KevoreeCoreThreadFactory(getNodeName()))
        val uuidModel = UUIDModelImpl(UUID.randomUUID(), factory.createContainerRoot())
        model.set(uuidModel)
    }


    fun stop() {
        Log.warn("Kevoree Core will be stopped !")
        modelListeners.stop()
        scheduler?.shutdownNow()
        scheduler = null
        if (nodeInstance != null) {
            try {
                val modelCurrent = model.get()!!.getModel()!!
                val stopModel = modelCloner.clone(modelCurrent)!!
                val currentNode = stopModel.findNodesByID(getNodeName())!!
                for (childNode in currentNode.hosts) {
                    childNode.started = false
                }
                //TEST only stop local
                for (group in stopModel.groups) {
                    group.started = false
                }
                for (hub in stopModel.hubs) {
                    hub.started = false
                }
                for (childComponent in currentNode.components) {
                    childComponent.started = false
                }

                //val stopModel = factory.createContainerRoot()
                val adaptationModel = nodeInstance!!.plan(modelCurrent, stopModel)
                val afterUpdateTest: () -> Boolean = {() -> true }
                val rootNode = modelCurrent.findByPath("nodes[" + getNodeName() + "]") as ContainerNode
                if (rootNode != null) {
                    PrimitiveCommandExecutionHelper.execute(this,rootNode, adaptationModel, nodeInstance!!, afterUpdateTest, afterUpdateTest, afterUpdateTest)
                } else {
                    Log.error("Node is not defined into the model so unbootstrap cannot be correctly done")
                }
            } catch (e: Exception) {
                Log.error("Error while unbootstrap ", e)
            }
            try {
                Log.debug("Call instance stop")

                if (nodeInstance != null) {
                    val met = resolver?.resolve(javaClass<org.kevoree.annotation.Stop>())
                    met?.invoke(nodeInstance)
                    nodeInstance == null
                    resolver = null
                }

                bootstrapService?.clear()
            } catch(e: Exception) {
                Log.error("Error while stopping node instance ", e)
            }
        }
        Log.debug("Kevoree core stopped ")
        broadcastTelemetry("info","Kevoree Stopped","")
    }

    inner class AcquireLock(val callBack: LockCallBack, val timeout: Long) : Runnable {
        override fun run() {
            if (currentLock != null) {
                try {
                    callBack.run(null, true)
                } catch (t: Throwable) {
                    Log.error("Exception inside a LockCallback with argument {}, {}", t, null, true)
                }
            } else {
                val lockUUID = UUID.randomUUID()
                currentLock = TupleLockCallBack(lockUUID, callBack)
                lockWatchDog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                futurWatchDog = lockWatchDog?.schedule(WatchDogCallable(), timeout, TimeUnit.MILLISECONDS)
                try {
                    callBack.run(lockUUID, false)
                } catch (t: Throwable) {
                    Log.error("Exception inside a LockCallback with argument {}, {}", t, lockUUID.toString(), false)
                }
            }
        }
    }

    inner class WatchDogCallable() : Runnable {
        override fun run() {
            lockTimeout()
        }
    }

    override fun releaseLock(uuid: UUID?, callerPath: String?) {
        if (uuid != null) {
            scheduler?.submit(ReleaseLockCallable(uuid))
        } else {
            Log.error("ReleaseLock method of Kevoree Core called with null argument, can release any lock")
        }
    }

    inner class ReleaseLockCallable(val uuid: UUID) : Runnable {
        override fun run() {
            if (currentLock != null) {
                if (currentLock!!.uuid.compareTo(uuid) == 0) {
                    currentLock = null
                    futurWatchDog?.cancel(true)
                    futurWatchDog = null
                    lockWatchDog?.shutdownNow()
                    lockWatchDog = null
                }
            }
        }
    }

    private fun lockTimeout() {
        scheduler?.submit(LockTimeoutCallable())
    }

    inner class LockTimeoutCallable() : Runnable {
        override fun run() {
            if (currentLock != null) {
                try {
                    currentLock!!.callback.run(null, true)
                } catch (t: Throwable) {
                    Log.error("Exception inside a LockCallback when it is called from the timeout trigger", t)
                }
                currentLock = null
                lockWatchDog?.shutdownNow()
                lockWatchDog = null
                futurWatchDog = null
            }
        }
    }

    fun internal_update_model(proposedNewModel: ContainerRoot, callerPath: String?): Boolean {

        if (proposedNewModel.findNodesByID(getNodeName()) == null) {
            Log.error("Asking for update with a NULL model or node name ({}) was not found in target model !", getNodeName())
            return false
        }

        try {
            var readOnlyNewModel = proposedNewModel

            if (readOnlyNewModel.isReadOnly()) {
                readOnlyNewModel = modelCloner.clone(readOnlyNewModel, false)!!;
                readOnlyNewModel.generated_KMF_ID = _nodeName + "@" + callerPath + "#" + System.nanoTime()
                readOnlyNewModel = modelCloner.clone(readOnlyNewModel, true)!!;
            } else {
                readOnlyNewModel.generated_KMF_ID = _nodeName + "@" + callerPath + "#" + System.nanoTime()
            }


            val checkResult = null//modelChecker.check(readOnlyNewModel)!!
            if ( checkResult != null) {
                Log.error("There is check failure on update model, update refused !")
                //for(cr in checkResult) {
                //    Log.error("error=> " + cr.getMessage() + ",objects" + cr.getTargetObjects())
                //}

                return false
            } else {

                pending = proposedNewModel

                //Model check is OK.
                var currentModel = model.get()!!.getModel()!!
                Log.debug("Before listeners PreCheck !")

                val updateContext = UpdateContext(currentModel, readOnlyNewModel, callerPath)

                val preCheckResult = modelListeners.preUpdate(updateContext)
                Log.debug("PreCheck result = " + preCheckResult)
                Log.debug("Before listeners InitUpdate !")
                val initUpdateResult = modelListeners.initUpdate(updateContext)
                Log.debug("InitUpdate result = " + initUpdateResult)
                if (preCheckResult && initUpdateResult) {
                    var newmodel = readOnlyNewModel
                    //CHECK FOR HARA KIRI
                    var previousHaraKiriModel: ContainerRoot? = null
                    if (/*hkh.detectNodeHaraKiri(currentModel, readOnlyNewModel, getNodeName())*/false) {
                        Log.warn("HaraKiri detected , flush platform")
                        previousHaraKiriModel = currentModel
                        // Creates an empty model, removes the current node (harakiri)
                        newmodel = factory.createContainerRoot()
                        try {
                            // Compare the two models and plan the adaptation
                            val adaptationModel = nodeInstance!!.plan(currentModel, newmodel)
                            if (Log.DEBUG) {
                                //Avoid the loop if the debug is not activated
                                Log.debug("Adaptation model size {}", adaptationModel.adaptations.size())
                            }
                            //Executes the adaptation
                            val afterUpdateTest: () -> Boolean = {() -> true }
                            val rootNode = currentModel.findNodesByID(getNodeName())
                            PrimitiveCommandExecutionHelper.execute(this,rootNode!!, adaptationModel, nodeInstance!!, afterUpdateTest, afterUpdateTest, afterUpdateTest)
                            if (nodeInstance != null) {
                                val met = resolver?.resolve(javaClass<org.kevoree.annotation.Stop>())
                                met?.invoke(nodeInstance)
                            }
                            //end of harakiri
                            nodeInstance = null
                            resolver = null
                            bootstrapService?.clear() //CLEAR
                            //place the current model as an empty model (for backup)

                            val backupEmptyModel = kevoreeFactory.createContainerRoot()
                            backupEmptyModel.setInternalReadOnly()
                            switchToNewModel(backupEmptyModel)

                            //prepares for deployment of the new system
                            newmodel = readOnlyNewModel
                        } catch(e: Exception) {
                            Log.error("Error while update ", e);return false
                        }
                        Log.debug("End HaraKiri")
                    }


                    //Checks and bootstrap the node
                    checkBootstrapNode(newmodel)
                    currentModel = model.get()!!.getModel()!!
                    val milli = System.currentTimeMillis()
                    if (Log.DEBUG) {
                        Log.debug("Begin update model {}", milli)
                    }
                    var deployResult: Boolean// = true
                    try {
                        if (nodeInstance != null) {
                            // Compare the two models and plan the adaptation
                            Log.info("Comparing models and planning adaptation.")

                            val adaptationModel = nodeInstance!!.plan(currentModel, newmodel)
                            //Execution of the adaptation
                            Log.info("Launching adaptation of the system.")
                            val updateContext = UpdateContext(currentModel, newmodel, callerPath)
                            val afterUpdateTest: () -> Boolean = {() -> modelListeners.afterUpdate(updateContext) }
                            val preCmd = PreCommand(updateContext, modelListeners)
                            val postRollbackTest: () -> Boolean = {() -> modelListeners.postRollback(updateContext);true }
                            val rootNode = newmodel.findNodesByID(getNodeName())!!
                            deployResult = PrimitiveCommandExecutionHelper.execute(this,rootNode, adaptationModel, nodeInstance!!, afterUpdateTest, preCmd.preRollbackTest, postRollbackTest)
                        } else {
                            Log.error("Node is not initialized")
                            deployResult = false
                        }
                    } catch(e: Exception) {
                        Log.error("Error while update ", e)
                        deployResult = false
                    }
                    if (deployResult) {
                        switchToNewModel(newmodel)
                        Log.info("Update sucessfully completed.")
                    } else {
                        //KEEP FAIL MODEL, TODO
                        Log.warn("Update failed")
                        //IF HARAKIRI
                        if (previousHaraKiriModel != null) {
                            internal_update_model(previousHaraKiriModel!!, callerPath)
                            previousHaraKiriModel = null //CLEAR
                        }
                    }
                    val milliEnd = System.currentTimeMillis() - milli
                    Log.debug("End deploy result={}-{}", deployResult, milliEnd)
                    pending = null
                    return deployResult

                } else {
                    Log.debug("PreCheck or InitUpdate Step was refused, update aborded !")
                    return false
                }

            }
        } catch (e: Throwable) {
            Log.error("Error while update", e)
            return false
        }
    }

    private fun bootstrapNodeType(model: ContainerRoot, nodeName: String): Any? {
        val nodeInstance = model.findNodesByID(nodeName)
        if (nodeInstance != null) {
            bootstrapService!!.installTypeDefinition(nodeInstance.typeDefinition!!)
            val newInstance = bootstrapService!!.createInstance(nodeInstance)!!
            bootstrapService!!.injectDictionary(nodeInstance, newInstance, false)
            return newInstance
        } else {
            Log.error("Node not found using name " + nodeName);
            return null
        }
    }

    private fun checkBootstrapNode(currentModel: ContainerRoot): Unit {
        try {
            if (nodeInstance == null) {
                val foundNode = currentModel.findNodesByID(getNodeName())
                if (foundNode != null) {
                    nodeInstance = bootstrapNodeType(currentModel, getNodeName()) as NodeType
                    if (nodeInstance != null) {
                        resolver = MethodAnnotationResolver(nodeInstance.javaClass)
                        val met = resolver?.resolve(javaClass<org.kevoree.annotation.Start>())
                        met?.invoke(nodeInstance)

                        val uuidModel = UUIDModelImpl(UUID.randomUUID(), factory.createContainerRoot())
                        model.set(uuidModel)
                    } else {
                        Log.error("TypeDef installation fail !")
                    }
                } else {
                    Log.error("Node instance name {} not found in bootstrap model !", getNodeName())
                }
            }
        } catch(e: Throwable) {
            Log.error("Error while bootstraping node instance ", e)
            // TODO is it possible to display the following log ?
            try {
                if (nodeInstance != null) {
                    val met = resolver?.resolve(javaClass<org.kevoree.annotation.Stop>())
                    met?.invoke(nodeInstance)
                }
            } catch(e: Throwable) {
            } finally {
                bootstrapService?.clear()
            }
            nodeInstance = null
            resolver = null
        }
    }

}

