package ru.r2cloud.satellite.reader;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.r2cloud.model.IQData;
import ru.r2cloud.model.ObservationRequest;
import ru.r2cloud.util.Configuration;
import ru.r2cloud.util.ProcessFactory;
import ru.r2cloud.util.ProcessWrapper;
import ru.r2cloud.util.Util;

public class RtlSdrReader implements IQReader {

	private static final Logger LOG = LoggerFactory.getLogger(RtlSdrReader.class);
	private static final int BUF_SIZE = 0x1000; // 4K

	private ProcessWrapper rtlSdr = null;
	private File wavPath;
	private Long startTimeMillis = null;
	private Long endTimeMillis = null;

	private final Configuration config;
	private final ProcessFactory factory;
	private final ObservationRequest req;

	public RtlSdrReader(Configuration config, ProcessFactory factory, ObservationRequest req) {
		this.config = config;
		this.factory = factory;
		this.req = req;
	}

	@Override
	public void start() {
		this.wavPath = new File(config.getTempDirectory(), req.getSatelliteId() + "-" + req.getId() + ".wav");
		ProcessWrapper sox = null;
		try {
			Integer ppm = config.getInteger("ppm.current");
			if (ppm == null) {
				ppm = 0;
			}
			sox = factory.create(config.getProperty("satellites.sox.path") + " --type raw --rate " + req.getInputSampleRate() + " --encoding unsigned-integer --bits 8 --channels 2 - " + wavPath.getAbsolutePath() + " rate " + req.getOutputSampleRate(), Redirect.INHERIT, false);
			rtlSdr = factory.create(config.getProperty("satellites.rtlsdr.path") + " -f " + String.valueOf(req.getActualFrequency()) + " -s " + req.getInputSampleRate() + " -g 45 -p " + String.valueOf(ppm) + " - ", Redirect.INHERIT, false);
			byte[] buf = new byte[BUF_SIZE];
			while (!Thread.currentThread().isInterrupted()) {
				int r = rtlSdr.getInputStream().read(buf);
				if (r == -1) {
					break;
				}
				if (startTimeMillis == null) {
					startTimeMillis = System.currentTimeMillis();
				}
				sox.getOutputStream().write(buf, 0, r);
			}
			sox.getOutputStream().flush();
		} catch (IOException e) {
			// there is no way to know if IOException was caused by closed stream
			if (e.getMessage() == null || !e.getMessage().equals("Stream closed")) {
				LOG.error("unable to run", e);
			}
		} finally {
			LOG.info("stopping pipe thread");
			Util.shutdown("rtl_sdr for satellites", rtlSdr, 10000);
			Util.shutdown("sox", sox, 10000);
			endTimeMillis = System.currentTimeMillis();
		}
	}

	@Override
	public IQData stop() {
		Util.shutdown("rtl_sdr for satellites", rtlSdr, 10000);
		rtlSdr = null;

		IQData result = new IQData();

		if (wavPath != null && wavPath.exists()) {
			result.setWavFile(wavPath);
		}
		if (startTimeMillis != null) {
			result.setActualStart(startTimeMillis);
		} else {
			// just to be on the safe side
			result.setActualStart(req.getStartTimeMillis());
		}
		if (endTimeMillis != null) {
			result.setActualEnd(endTimeMillis);
		} else {
			result.setActualEnd(req.getEndTimeMillis());
		}

		return result;
	}

}
