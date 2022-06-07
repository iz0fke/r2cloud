package ru.r2cloud.satellite.reader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.r2cloud.jradio.BeaconOutputStream;
import ru.r2cloud.jradio.RawBeacon;
import ru.r2cloud.jradio.RxMetadata;
import ru.r2cloud.lora.LoraObservationRequest;
import ru.r2cloud.lora.LoraResponse;
import ru.r2cloud.lora.LoraFrame;
import ru.r2cloud.lora.ResponseStatus;
import ru.r2cloud.lora.loraat.LoraAtClient;
import ru.r2cloud.model.IQData;
import ru.r2cloud.model.ObservationRequest;
import ru.r2cloud.model.Transmitter;
import ru.r2cloud.util.Configuration;

public class LoraAtReader implements IQReader {

	private static final Logger LOG = LoggerFactory.getLogger(LoraAtReader.class);

	private final Configuration config;
	private final ObservationRequest req;
	private final LoraAtClient client;
	private final Transmitter transmitter;
	private final CountDownLatch latch = new CountDownLatch(1);

	public LoraAtReader(Configuration config, ObservationRequest req, LoraAtClient client, Transmitter transmitter) {
		this.config = config;
		this.req = req;
		this.client = client;
		this.transmitter = transmitter;
	}

	@Override
	public IQData start() throws InterruptedException {
		LoraObservationRequest loraRequest = new LoraObservationRequest();
		loraRequest.setBw((float) transmitter.getLoraBandwidth() / 1000);
		loraRequest.setCr(transmitter.getLoraCodingRate());
		loraRequest.setFrequency((float) req.getActualFrequency() / 1_000_000);
		loraRequest.setGain((int) req.getGain());
		loraRequest.setLdro(transmitter.getLoraLdro());
		loraRequest.setPreambleLength(transmitter.getLoraPreambleLength());
		loraRequest.setSf(transmitter.getLoraSpreadFactor());
		loraRequest.setSyncword(transmitter.getLoraSyncword());
		LOG.info("[{}] starting lora observation for {} on {}Mhz", req.getId(), transmitter, loraRequest.getFrequency());
		LoraResponse response = client.startObservation(loraRequest);
		if (!response.getStatus().equals(ResponseStatus.SUCCESS)) {
			LOG.error("[{}] unable to start lora observation: {}", req.getId(), response.getFailureMessage());
			return null;
		}
		long startTimeMillis = System.currentTimeMillis();
		latch.await();
		response = client.stopObservation();
		long endTimeMillis = System.currentTimeMillis();
		if (!response.getStatus().equals(ResponseStatus.SUCCESS)) {
			LOG.error("[{}] unable to stop lora observation: {}", req.getId(), response.getFailureMessage());
			return null;
		}
		File rawFile = new File(config.getTempDirectory(), req.getSatelliteId() + "-" + req.getId() + ".raw");
		// raw file is the same as binary file
		// decoder just have to rename it
		try (BeaconOutputStream bos = new BeaconOutputStream(new BufferedOutputStream(new FileOutputStream(rawFile)))) {
			for (LoraFrame cur : response.getFrames()) {
				bos.write(convert(cur));
			}
		} catch (IOException e) {
			LOG.error("[{}] unable to save beacons", req.getId(), e);
		}
		LOG.info("[{}] observation completed", req.getId());
		IQData result = new IQData();
		result.setActualStart(startTimeMillis);
		result.setActualEnd(endTimeMillis);
		result.setDataFile(rawFile);
		return result;
	}

	private static RawBeacon convert(LoraFrame frame) {
		RawBeacon result = new RawBeacon();
		result.setBeginMillis(frame.getTimestamp() * 1000);
		result.setRawData(frame.getData());
		RxMetadata meta = new RxMetadata();
		meta.setFrequencyError((long) frame.getFrequencyError());
		meta.setRssi(frame.getRssi());
		meta.setSnr(frame.getSnr());
		result.setRxMeta(meta);
		return result;
	}

	@Override
	public void complete() {
		latch.countDown();
	}

}
