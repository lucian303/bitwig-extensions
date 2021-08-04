package com.bitwig.extensions.controllers.mackie.layer;

import java.util.function.BiConsumer;

import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extension.controller.api.Device;
import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extensions.controllers.mackie.bindings.ButtonBinding;
import com.bitwig.extensions.controllers.mackie.devices.DeviceManager;
import com.bitwig.extensions.controllers.mackie.devices.ParameterPage;
import com.bitwig.extensions.controllers.mackie.display.RingDisplayType;
import com.bitwig.extensions.framework.Layer;
import com.bitwig.extensions.framework.Layers;

class TrackLayerConfiguration extends LayerConfiguration {

	private final Layer faderLayer;
	private final Layer encoderLayer;
	private final DisplayLayer displayLayer;
	private final DisplayLayer infoLayer;
	private DeviceManager deviceManager;
	private String stdMissingTextLine1;
	private String stdMissingTextLine2;
	private final MenuModeLayerConfiguration menuControl;

	public TrackLayerConfiguration(final String name, final MixControl mixControl) {
		super(name, mixControl);
		final Layers layers = this.mixControl.getDriver().getLayers();
		final int sectionIndex = mixControl.getHwControls().getSectionIndex();
		faderLayer = new Layer(layers, name + "_FADER_LAYER_" + sectionIndex);
		encoderLayer = new Layer(layers, name + "_ENCODER_LAYER_" + sectionIndex);
		displayLayer = new DisplayLayer(name, this.mixControl);
		menuControl = new MenuModeLayerConfiguration(name + "_MENU_" + sectionIndex, mixControl);
		menuControl.getDisplayLayer(0).displayFullTextMode(true);
		infoLayer = new DisplayLayer(name + "_INFO", this.mixControl);
		infoLayer.enableFullTextMode(true);
	}

	@Override
	public DeviceManager getDeviceManager() {
		return deviceManager;
	}

	public boolean isActive() {
		return encoderLayer.isActive() || faderLayer.isActive();
	}

	public void setMissingText(final String line1, final String line2) {
		this.stdMissingTextLine1 = line1;
		this.stdMissingTextLine2 = line2;
		if (deviceManager != null) {
			deviceManager.getDevice().exists().addValueObserver(
					exist -> evaluateTextDisplay(getPagesCount(), exist, deviceManager.getDevice().name().get()));
		}
	}

	public void setDeviceManager(final DeviceManager deviceManager) {
		this.deviceManager = deviceManager;
		this.deviceManager.getCursorOnDevice().markInterested();
		this.deviceManager.setInfoLayer(infoLayer);
		final CursorRemoteControlsPage remotes = deviceManager.getRemote();
		final Device device = deviceManager.getDevice();
		device.name().markInterested();
		if (remotes != null) {
			remotes.pageCount()
					.addValueObserver(count -> evaluateTextDisplay(count, device.exists().get(), device.name().get()));
		}
		device.name().addValueObserver(name -> {
			evaluateTextDisplay(getPagesCount(), device.exists().get(), name);
		});

		final DisplayLayer menuDisplayLayer = menuControl.getDisplayLayer(0);
		menuDisplayLayer.bindBool(0, device.isEnabled(), "ACTIVE", "<BYPS>", device, "-no dev-");
		menuControl.addPressEncoderBinding(0, encIndex -> {
			device.isEnabled().toggle();
		});

		int slotcount = 1;
		if (deviceManager.isCanTrackMultiple()) {
			menuDisplayLayer.bindFixed(slotcount, "<Move");
			menuControl.addPressEncoderBinding(slotcount, encIndex -> {
				deviceManager.moveDeviceToLeft();
			});
			slotcount++;
			menuDisplayLayer.bindFixed(slotcount, "Move>");
			menuControl.addPressEncoderBinding(slotcount, encIndex -> {
				deviceManager.moveDeviceToRight();
			});
			slotcount++;
		}

		menuDisplayLayer.bindFixed(slotcount, "REMOVE");
		menuControl.addPressEncoderBinding(slotcount, encIndex -> {
			deviceManager.removeDevice();
		});

		for (int i = 1; i < 8; i++) {
			menuControl.addRingFixedBinding(i);
		}

		menuDisplayLayer.bindFixed(7, "Browse");
		menuControl.addRingBoolBinding(0, device.isEnabled());
	}

	private int getPagesCount() {
		if (deviceManager.getRemote() == null) {
			return 0;
		}
		return deviceManager.getRemote().pageCount().get();
	}

	private void evaluateTextDisplay(final int count, final boolean exists, final String deviceName) {
		if (deviceManager == null) {
			return;
		}
		final DisplayLayer menuLayer = menuControl.getDisplayLayer(0);
		menuLayer.setText(0, "Device: " + deviceManager.getDevice().name().get(), false);
		menuLayer.enableFullTextMode(0, true);
		final CursorRemoteControlsPage remotes = deviceManager.getRemote();
		final Device device = deviceManager.getDevice();
		if (remotes != null) {
			if (!exists) {
				displayLayer.setMainText(stdMissingTextLine1, stdMissingTextLine2, true);
				displayLayer.enableFullTextMode(true);
			} else if (count == 0) {
				displayLayer.setMainText(device.name().get() + " has no Parameter Pages",
						"<< configure Parameter Pages in Bitwig >>", true);
				displayLayer.enableFullTextMode(true);
			} else {
				displayLayer.enableFullTextMode(false);
			}
		} else if (!exists) {
			displayLayer.setMainText(stdMissingTextLine1, stdMissingTextLine2, true);
			displayLayer.enableFullTextMode(true);
		} else {
			displayLayer.enableFullTextMode(false);
		}
	}

	@Override
	public Layer getFaderLayer() {
		final boolean flipped = this.mixControl.driver.getFlipped().get();
		final boolean isMixerGlobal = this.mixControl.driver.getGlobalViewActive().get();
		if (flipped) {
			return faderLayer;
		}
		if (isMixerGlobal) {
			return this.mixControl.globalGroup.getFaderLayer(ParamElement.VOLUME);
		}
		return this.mixControl.mainGroup.getFaderLayer(ParamElement.VOLUME);
	}

	@Override
	public Layer getEncoderLayer() {
		if (mixControl.getIsMenuHoldActive().get()) {
			return menuControl.getEncoderLayer();
		}
		final boolean flipped = this.mixControl.driver.getFlipped().get();
		final boolean isMixerGlobal = this.mixControl.driver.getGlobalViewActive().get();
		if (flipped) {
			if (isMixerGlobal) {
				return this.mixControl.globalGroup.getEncoderLayer(ParamElement.VOLUME);
			}
			return this.mixControl.mainGroup.getEncoderLayer(ParamElement.VOLUME);
		}
		return encoderLayer;
	}

	@Override
	public DisplayLayer getDisplayLayer(final int which) {
		if (mixControl.getIsMenuHoldActive().get()) {
			return menuControl.getDisplayLayer(0);
		}
		if (deviceManager != null && deviceManager.getInfoSource() != null) {
			return infoLayer;
		}
		if (which == 0) {
			return displayLayer;
		}
		if (mixControl.driver.getGlobalViewActive().get()) {
			return mixControl.globalGroup.getDisplayConfiguration(ParamElement.VOLUME);
		}
		return mixControl.mainGroup.getDisplayConfiguration(ParamElement.VOLUME);
	}

	@Override
	public boolean enableInfo(final InfoSource type) {
		deviceManager.enableInfo(type);
		return true;
	}

	@Override
	public boolean disableInfo() {
		deviceManager.disableInfo();
		return true;
	}

	public void addBinding(final int index, final ParameterPage parameter,
			final BiConsumer<Integer, ParameterPage> resetAction) {
		final MixerSectionHardware hwControls = mixControl.getHwControls();
		encoderLayer.addBinding(parameter.getRelativeEncoderBinding(hwControls.getEncoder(index)));
		encoderLayer.addBinding(parameter.createRingBinding(hwControls.getRingDisplay(index)));
		encoderLayer.addBinding(new ButtonBinding(hwControls.getEncoderPress(index),
				hwControls.createAction(() -> resetAction.accept(index, parameter))));

		faderLayer.addBinding(parameter.getFaderBinding(hwControls.getVolumeFader(index)));
		faderLayer.addBinding(parameter.createFaderBinding(hwControls.getMotorFader(index)));

		displayLayer.bind(index, parameter);
		parameter.resetBindings();
	}

	public void addBinding(final int index, final Parameter parameter, final RingDisplayType type) {
		final MixerSectionHardware hwControls = mixControl.getHwControls();

		faderLayer.addBinding(hwControls.createMotorFaderBinding(index, parameter));
		faderLayer.addBinding(hwControls.createFaderParamBinding(index, parameter));
		faderLayer.addBinding(hwControls.createFaderTouchBinding(index, () -> {
			if (mixControl.getModifier().isShift()) {
				parameter.reset();
			}
		}));
		encoderLayer.addBinding(hwControls.createEncoderPressBinding(index, parameter));
		encoderLayer.addBinding(hwControls.createEncoderToParamBinding(index, parameter));
		encoderLayer.addBinding(hwControls.createRingDisplayBinding(index, parameter, type));
		displayLayer.bindName(index, parameter.name());
		displayLayer.bindParameterValue(index, parameter);
	}

}