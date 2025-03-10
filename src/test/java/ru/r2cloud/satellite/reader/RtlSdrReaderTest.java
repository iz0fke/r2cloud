package ru.r2cloud.satellite.reader;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ru.r2cloud.TestConfiguration;
import ru.r2cloud.model.IQData;
import ru.r2cloud.model.ObservationRequest;
import ru.r2cloud.satellite.ProcessFactoryMock;
import ru.r2cloud.satellite.ProcessWrapperMock;

public class RtlSdrReaderTest {

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private TestConfiguration config;
	private String rtlsdr;
	private String rtlBiast;

	@Test
	public void testBiasTSuccess() throws Exception {
		String satelliteId = UUID.randomUUID().toString();
		ProcessFactoryMock factory = new ProcessFactoryMock(create(new ProcessWrapperMock(null, null, 143, true), new ProcessWrapperMock(null, null, 0)), satelliteId);

		ObservationRequest req = new ObservationRequest();
		req.setBiast(true);
		req.setSatelliteId(satelliteId);

		RtlSdrReader o = new RtlSdrReader(config, factory, req);
		IQData iqData = o.start();
		o.complete();
		assertNotNull(iqData.getDataFile());
	}

	@Test
	public void testBiasTFailure() throws Exception {
		String satelliteId = UUID.randomUUID().toString();
		ProcessFactoryMock factory = new ProcessFactoryMock(create(new ProcessWrapperMock(null, null, 143, true), new ProcessWrapperMock(null, null, 1)), satelliteId);

		ObservationRequest req = new ObservationRequest();
		req.setBiast(true);
		req.setSatelliteId(satelliteId);

		RtlSdrReader o = new RtlSdrReader(config, factory, req);
		IQData iqData = o.start();
		o.complete();
		assertNull(iqData);
	}

	@Test
	public void testFailure() throws Exception {
		String satelliteId = UUID.randomUUID().toString();
		ProcessFactoryMock factory = new ProcessFactoryMock(create(new ProcessWrapperMock(null, null, 0)), satelliteId);

		ObservationRequest req = new ObservationRequest();
		req.setBiast(false);
		req.setSatelliteId(satelliteId);

		RtlSdrReader o = new RtlSdrReader(config, factory, req);
		IQData iqData = o.start();
		o.complete();
		assertNull(iqData.getDataFile());
	}

	@Test
	public void testSuccess() throws Exception {
		String satelliteId = UUID.randomUUID().toString();
		ProcessFactoryMock factory = new ProcessFactoryMock(create(new ProcessWrapperMock(null, null, 143, true)), satelliteId);

		ObservationRequest req = new ObservationRequest();
		req.setBiast(false);
		req.setSatelliteId(satelliteId);

		RtlSdrReader o = new RtlSdrReader(config, factory, req);
		IQData iqData = o.start();
		o.complete();
		assertNotNull(iqData.getDataFile());
	}

	@Before
	public void start() throws Exception {
		rtlsdr = UUID.randomUUID().toString();
		rtlBiast = UUID.randomUUID().toString();

		config = new TestConfiguration(tempFolder);
		config.setProperty("satellites.rtlsdrwrapper.path", rtlsdr);
		config.setProperty("satellites.rtlsdr.biast.path", rtlBiast);
		config.setProperty("server.tmp.directory", tempFolder.getRoot().getAbsolutePath());
		config.update();
	}

	private Map<String, ProcessWrapperMock> create(ProcessWrapperMock rtl) {
		return create(rtl, null);
	}

	private Map<String, ProcessWrapperMock> create(ProcessWrapperMock rtl, ProcessWrapperMock bias) {
		Map<String, ProcessWrapperMock> result = new HashMap<>();
		result.put(rtlsdr, rtl);
		result.put(rtlBiast, bias);
		return result;
	}

}
