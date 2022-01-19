package ru.r2cloud.satellite.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ru.r2cloud.jradio.BeaconOutputStream;
import ru.r2cloud.model.DecoderResult;
import ru.r2cloud.model.LoraBeacon;
import ru.r2cloud.model.ObservationRequest;

public class R2loraDecoderTest {

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@Test
	public void testSuccess() throws Exception {
		LoraBeacon beacon = new LoraBeacon();
		beacon.setBeginMillis(1641987504000L);
		beacon.setRawData(new byte[] { 0x11, 0x22 });
		File rawFile = new File(tempFolder.getRoot(), UUID.randomUUID().toString() + ".raw");
		try (BeaconOutputStream bos = new BeaconOutputStream(new FileOutputStream(rawFile))) {
			bos.write(beacon);
		}

		R2loraDecoder decoder = new R2loraDecoder();
		DecoderResult result = decoder.decode(rawFile, new ObservationRequest());
		assertNotNull(result);
		assertEquals(1, result.getNumberOfDecodedPackets().longValue());
		assertNotNull(result.getDataPath());
	}

	@Test
	public void testNoDataOrInvalid() throws Exception {
		File rawFile = new File(tempFolder.getRoot(), UUID.randomUUID().toString() + ".raw");
		try (FileOutputStream fos = new FileOutputStream(rawFile)) {
			fos.write(1);
		}
		R2loraDecoder decoder = new R2loraDecoder();
		DecoderResult result = decoder.decode(rawFile, new ObservationRequest());
		assertNotNull(result);
		assertEquals(0, result.getNumberOfDecodedPackets().longValue());
		assertNull(result.getDataPath());
		assertFalse(rawFile.exists());
	}

}