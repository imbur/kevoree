package org.kevoree.tools.annotation.mavenplugin.traversal;

import java.util.List;
import java.util.Objects;

import org.apache.maven.plugin.logging.Log;
import org.json.JSONException;
import org.kevoree.DeployUnit;
import org.kevoree.TypeDefinition;
import org.kevoree.Value;
import org.kevoree.factory.DefaultKevoreeFactory;
import org.kevoree.registry.client.api.RegistryRestClient;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.exceptions.UnirestException;

public class CreateDeployUnit extends TraverseModel {

	private final RegistryRestClient client;
	private final Log log;

	public CreateDeployUnit(final RegistryRestClient client, final Log log) {
		this.client = client;
		this.log = log;
	}

	@Override
	public void visitDeployUnit(final String namespace, final DeployUnit deployUnit, final String tdefName,
			final String tdefVersion) throws UnirestException {

		try {
			final String platform = getPlatform(deployUnit);
			final String model = new DefaultKevoreeFactory().createJSONSerializer().serialize(deployUnit);
			final String duName = deployUnit.getName();
			final String duVersion = deployUnit.getVersion();

			this.log.debug("Create deploy unit : namespace=" + namespace + ", typedefname=" + tdefName
					+ ", typedefversion=" + tdefVersion + ", platform=" + platform + ", duname=" + duName
					+ ", duversion=" + duVersion);

			final HttpResponse<JsonNode> getClientRes = this.client.getDeployUnit(namespace, tdefName, tdefVersion,
					platform, duName, duVersion);

			final HttpResponse<JsonNode> res;
			if (getClientRes.getStatus() < 400) {
				res = this.client.putDeployUnit(namespace, tdefName, tdefVersion, platform, model, duName, duVersion,
						getClientRes.getBody().getObject().getLong("id"));
			} else {
				res = this.client.postDeployUnit(namespace, tdefName, tdefVersion, platform, model, duName, duVersion);
			}

			if (res != null && res.getStatus() >= 400) {
				this.log.error(res.getBody().toString());
			}

		} catch (final Exception e) {
			this.log.error(e);
		}

	}

	private String getPlatform(final DeployUnit deployUnit) {
		String platform = "";
		for (final Value t : deployUnit.getFilters()) {
			if (Objects.equals(t.getName(), "platform")) {
				platform = t.getValue();
				break;
			}
		}
		return platform;
	}

	@Override
	public void visitTypeDefinition(final String namespace, final TypeDefinition typeDefinition)
			throws JSONException, UnirestException, TraverseModelException {
		final String name = typeDefinition.getName();
		final String version = typeDefinition.getVersion();
		final HttpResponse<JsonNode> typeDef = this.client.getTypeDef(namespace, name, version);
		final String oldVersion = typeDef.getBody().getObject().getString("model");
		final String newVersion = new DefaultKevoreeFactory().createJSONSerializer().serialize(typeDefinition);
		if (!Objects.equals(oldVersion, newVersion)) {
			throw new TraverseModelException("Generated type definition has changed and does not match " + namespace
					+ ":" + name + ":" + version + ".");
		}
	}

	@Override
	public void visitPackage(final List<String> npackages) throws UnirestException {

	}

	@Override
	public void handlerTypeDefError(final TraverseModelException e) {
		this.log.error(e.getMessage());

	}

}
