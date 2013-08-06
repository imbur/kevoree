/**
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE, Version 3, 29 June 2007;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kevoree.framework.port

import org.kevoree.framework.KevoreePort
import java.util.concurrent.LinkedBlockingDeque
import org.kevoree.log.Log

/**
 * Created with IntelliJ IDEA.
 * User: duke
 * Date: 04/10/12
 * Time: 11:58
 */
trait KevoreeProvidedThreadPort: KevoreePort, Runnable {

    var queue: java.util.concurrent.LinkedBlockingDeque<Any?>?
    var reader: Thread?
    var tg : ThreadGroup?
    var isPaused: Boolean

    override fun send(o: Any?) {
        queue?.add(o)
    }

    override fun sendWait(o: Any?): Any {
        throw Exception("Bad Message Port Usages")
    }

    override fun startPort(_tg : ThreadGroup?) {
        tg = _tg
    }

    override fun stop() {
        queue?.clear()
        queue = null
        reader?.interrupt()
        reader = null
    }

    override fun pause() {
        stop()
        isPaused = true
    }

    override fun forceStop() {
        stop()
    }

    override fun resume() {
        queue = LinkedBlockingDeque<Any?>()
        if (reader == null && tg != null) {
            reader = Thread(tg!!,this)
            reader!!.start()
        }
        isPaused = false
    }

    override fun processAdminMsg(o: Any): Boolean {
        //NO BIND MESSAGE
        return true
    }

    override fun run() {
        while (true) {
            //TO CLEAN STOP
            try {
                var obj = queue?.take()
                if (obj != null) {
                    internal_process(obj)
                }
            } catch(e : InterruptedException){
                Log.warn("Interrupted Provided Port, possible message lost !")
            }
        }

    }

    fun internal_process(o: Any?) : Any?

    override fun isInPause(): Boolean {
        return isPaused
    }

}