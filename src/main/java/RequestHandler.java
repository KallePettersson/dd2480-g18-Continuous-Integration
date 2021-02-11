import com.google.gson.*;
import org.eclipse.egit.github.core.CommitStatus;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.CommitService;
import org.eclipse.egit.github.core.service.RepositoryService;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Map;

public class RequestHandler extends Thread {
	volatile String data;

	@Override
	public void run() {

		JsonObject jsonObject = new JsonParser().parse(data).getAsJsonObject();

		try {
			sendCommitStatus(jsonObject, CommitStatus.STATE_PENDING, "Build started");
		}catch (Exception e){}

		boolean savedLocally = jsonHandler.local;
		String branch = jsonObject.get("ref").getAsString().split("/")[2];
		String commit = jsonObject.get("after").getAsString().substring(0,7);

		// perform the integration build with unit tests and save the resulting statuses
		Map<String, String> statuses = Integrator.integrateBuild(branch, commit, savedLocally);
		Build build = new Build(ContinuousIntegrationServer.db.getNextBuildID(), commit, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Calendar.getInstance().getTime()),
				branch, statuses.get(Integrator.STATUS_INSTALL), statuses.get(Integrator.STATUS_COMPILE), statuses.get(Integrator.STATUS_TEST),
				savedLocally);
		ContinuousIntegrationServer.json.saveGithubLogs(jsonObject, commit);
		ContinuousIntegrationServer.db.addBuildToDB(build);
		ContinuousIntegrationServer.json.saveBuildHistory(ContinuousIntegrationServer.db);

		// if tests succeed, everything has succeeded
		String testStatus = statuses.get(Integrator.STATUS_TEST);
		if (testStatus.equals(Integrator.STATUS_SUCCESS)) { //successfull build
			try {
				sendCommitStatus(jsonObject, CommitStatus.STATE_SUCCESS, "All tests passed");
			}catch (Exception e){}
		}else{ //failed build
			try {
				sendCommitStatus(jsonObject, CommitStatus.STATE_FAILURE, "Build failed");
			}catch (Exception e){}
		}
	}

	private static void sendCommitStatus(JsonObject commit, String commitStatus, String description) throws IOException {
		GitHubClient ghc = new GitHubClient(); // Create GitHub Client
		ghc.setOAuth2Token(System.getProperty("DD2480_TOKEN")); // Authenticate.
		RepositoryService rs = new RepositoryService(ghc); // get repo service
		CommitService cs = new CommitService(ghc);

		Repository repo = rs.getRepository(commit.getAsJsonObject("repository").getAsJsonObject("owner").get("name").getAsString(), commit.getAsJsonObject("repository").get("name").getAsString()); // get the repository
		// Set commit status
		CommitStatus status = cs.createStatus(repo, commit.get("after").getAsString(), new CommitStatus().setState(commitStatus).setDescription(description));
	}

}