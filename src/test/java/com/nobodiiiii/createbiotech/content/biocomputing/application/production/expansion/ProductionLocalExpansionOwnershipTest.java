package com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion;

import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionLocalExpansionOwnershipTest {
	@Test void actualHostComposesOneLocalExpansionOwner() throws Exception {
		Class<?> host = Class.forName("com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$Runtime");
		assertTrue(Arrays.stream(host.getDeclaredFields()).anyMatch(field ->
			field.getType().getName().equals(
				"com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion")));
		assertFalse(Arrays.stream(host.getDeclaredFields()).anyMatch(field ->
			field.getName().equals("localComputationJobs")));
	}
	@Test void actualTickAndRecoveryComposeTheOwnerWithoutOldPolicyForwarders() throws Exception {
		String host = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FactoryOrchestrationServer.java"));
		int begin = host.indexOf("private void tick() {");
		String tick = host.substring(begin, host.indexOf("private void refreshLogisticsAddressDirectory()", begin));
		assertTrue(tick.indexOf("planDispatch.advance();") < tick.indexOf("localExpansion.advance();"));
		assertTrue(tick.indexOf("localExpansion.advance();") < tick.indexOf("remoteExpansion.advanceJobs();"));
		assertTrue(tick.indexOf("advanceOutputContracts();") < tick.indexOf("localExpansion.retireCompleted();"));
		assertTrue(host.indexOf("remoteExpansion.restore();") < host.indexOf("localExpansion.restore();"));
		for (String removed : List.of("localComputationJobs", "beginLocalComputation(", "advanceLocalComputationJobs(", "persistLocalComputation(", "terminateLocalComputation(", "retireCompletedLocalComputations(", "capturePureExpansionRequest("))
			assertFalse(host.contains(removed), removed + " is no longer host authority");
		assertTrue(Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/content/biocomputing/application/production/expansion/RemoteExpansionProtocol.java")).contains("PureExpansionCapture.capture("));
		assertFalse(Arrays.stream(Class.forName("com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer").getDeclaredClasses()).anyMatch(type -> type.getSimpleName().startsWith("LocalComputation")));
	}
}

