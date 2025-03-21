package ru.r2cloud.satellite.decoder;

import ru.r2cloud.jradio.Beacon;
import ru.r2cloud.jradio.BeaconSource;
import ru.r2cloud.jradio.ByteInput;
import ru.r2cloud.jradio.blocks.SoftToHard;
import ru.r2cloud.jradio.opssat.OpsSat;
import ru.r2cloud.jradio.opssat.OpsSatBeacon;
import ru.r2cloud.model.ObservationRequest;
import ru.r2cloud.predict.PredictOreKit;
import ru.r2cloud.util.Configuration;

public class OpsSatDecoder extends TelemetryDecoder {

	public OpsSatDecoder(PredictOreKit predict, Configuration config) {
		super(predict, config);
	}

	@Override
	public BeaconSource<? extends Beacon> createBeaconSource(ByteInput demodulator, ObservationRequest req) {
		SoftToHard bs = new SoftToHard(demodulator);
		return new OpsSat(bs);
	}

	@Override
	public Class<? extends Beacon> getBeaconClass() {
		return OpsSatBeacon.class;
	}
}
