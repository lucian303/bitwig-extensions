package com.bitwig.extensions.controllers.mackie;

import java.util.ArrayList;
import java.util.List;

import com.bitwig.extension.api.util.midi.ShortMidiMessage;
import com.bitwig.extension.callback.ShortMidiMessageReceivedCallback;
import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.AbsoluteHardwareKnob;
import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.Arranger;
import com.bitwig.extension.controller.api.BeatTimeFormatter;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CueMarker;
import com.bitwig.extension.controller.api.CueMarkerBank;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.DeviceMatcher;
import com.bitwig.extension.controller.api.HardwareActionBindable;
import com.bitwig.extension.controller.api.HardwareButton;
import com.bitwig.extension.controller.api.HardwareSurface;
import com.bitwig.extension.controller.api.MasterTrack;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.OnOffHardwareLight;
import com.bitwig.extension.controller.api.PinnableCursorDevice;
import com.bitwig.extension.controller.api.Project;
import com.bitwig.extension.controller.api.RelativeHardwareKnob;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.TrackBank;
import com.bitwig.extension.controller.api.Transport;
import com.bitwig.extensions.controllers.mackie.bindings.FaderBinding;
import com.bitwig.extensions.controllers.mackie.devices.DeviceTracker;
import com.bitwig.extensions.controllers.mackie.devices.Devices;
import com.bitwig.extensions.controllers.mackie.devices.EqDevice;
import com.bitwig.extensions.controllers.mackie.display.TimeCodeLed;
import com.bitwig.extensions.controllers.mackie.display.VuMode;
import com.bitwig.extensions.controllers.mackie.layer.LayerConfiguration;
import com.bitwig.extensions.controllers.mackie.layer.MenuModeLayerConfiguration;
import com.bitwig.extensions.controllers.mackie.layer.MixControl;
import com.bitwig.extensions.controllers.mackie.layer.SectionType;
import com.bitwig.extensions.controllers.mackie.targets.MotorFader;
import com.bitwig.extensions.controllers.mackie.value.BooleanValueObject;
import com.bitwig.extensions.controllers.mackie.value.LayoutType;
import com.bitwig.extensions.controllers.mackie.value.ModifierValueObject;
import com.bitwig.extensions.controllers.mackie.value.TrackModeValue;
import com.bitwig.extensions.framework.Layer;
import com.bitwig.extensions.framework.Layers;
import com.bitwig.extensions.remoteconsole.RemoteConsole;

public class MackieMcuProExtension extends ControllerExtension {

	private static final String SYSEX_DEVICE_RELOAD = "f0000066140158595a";
	private static final double[] FFWD_SPEEDS = { 0.0625, 0.25, 1.0, 4.0 };
	private static final double[] FFWD_SPEEDS_SHIFT = { 0.25, 1.0, 4.0, 16.0 };
	private static final long[] FFWD_TIMES = { 500, 1000, 2000, 3000, 4000 };

	private Layers layers;
	private Layer mainLayer;
	private Layer shiftLayer;

	private HardwareSurface surface;
	private Transport transport;
	private Application application;
	private Project project;
	private MidiOut midiOut;
	private MidiIn midiIn;
	private CursorTrack cursorTrack;
	private TrackBank mixerTrackBank;
	private TrackBank globalTrackBank;
	private ControllerHost host;
	private TimeCodeLed ledDisplay;
	private MasterTrack masterTrack;
	private final BooleanValueObject flipped = new BooleanValueObject();
	private final BooleanValueObject zoomActive = new BooleanValueObject();
	private final BooleanValueObject scrubActive = new BooleanValueObject();
	private final BooleanValueObject globalViewActive = new BooleanValueObject();
	private final BooleanValueObject groupViewActive = new BooleanValueObject();

	private final ModifierValueObject modifier = new ModifierValueObject();
	private final TrackModeValue trackChannelMode = new TrackModeValue();

	private VuMode vuMode = VuMode.LED;
	private final int nrOfExtenders;
	private DelayAction delayedAction = null; // TODO this needs to be a queue
	private PinnableCursorDevice cursorDevice;

	private final HoldMenuButtonState holdAction = new HoldMenuButtonState();
	private final int[] lightStatusMap = new int[127];

	private EqDevice eqDevice;
	private DeviceTracker instrumentDevice;
	private DeviceTracker pluginDevice;
	private LayoutType currentLayoutType;

	private MixControl mainSection;
	private final List<MixControl> sections = new ArrayList<>();

	private final HoldCapture holdState = new HoldCapture();
	private Arranger arranger;

	protected MackieMcuProExtension(final ControllerExtensionDefinition definition, final ControllerHost host,
			final int extenders) {
		super(definition, host);
		this.nrOfExtenders = extenders;
	}

	@Override
	public void init() {
		host = getHost();
		surface = host.createHardwareSurface();
		transport = host.createTransport();
		application = host.createApplication();
		arranger = host.createArranger();
		project = host.getProject();
		layers = new Layers(this);
		mainLayer = new Layer(layers, "MainLayer");
		shiftLayer = new Layer(layers, "GlobalShiftLayer");

		for (int i = 0; i < lightStatusMap.length; i++) {
			lightStatusMap[i] = -1;
		}

		midiOut = host.getMidiOutPort(0);
		midiIn = host.getMidiInPort(0);
		ledDisplay = new TimeCodeLed(midiOut);

		initJogWheel();
		initMasterSection();
		initChannelSections();
		intiVPotModes();

		initTransport();
		initTrackBank(4);
		initModifiers();

		initCursorSection();

		midiIn.setMidiCallback((ShortMidiMessageReceivedCallback) msg -> onMidi0(msg));

		setUpMidiSysExCommands();
		mainLayer.activate();
		host.showPopupNotification(" Initialized Mackie MCU Pro");
		sections.forEach(MixControl::resetFaders);
		sections.forEach(MixControl::clearAll);
		ledDisplay.setAssignment("PN", false);
		ledDisplay.refreschMode();
		host.scheduleTask(this::handlePing, 100);
//		final Action[] as = application.getActions();
//		for (final Action action : as) {
//			RemoteConsole.out.println("ACTION > [{}]", action.getId());
//		}
	}

	public void initChannelSections() {
		mainSection = new MixControl(this, midiIn, midiOut, 0, SectionType.MAIN);
		sections.add(mainSection);
		for (int i = 0; i < nrOfExtenders; i++) {
			final MidiOut extMidiOut = host.getMidiOutPort(i + 1);
			final MidiIn extMidiIn = host.getMidiInPort(i + 1);
			if (extMidiIn != null && extMidiOut != null) {
				final MixControl extenderSection = new MixControl(this, extMidiIn, extMidiOut, i + 1,
						SectionType.XTENDER);
				sections.add(extenderSection);
			} else {
				// RemoteConsole.out.println(" CREATE Extender Section {} failed due to missing
				// ports", i + 1);
			}
		}
	}

	public void doActionImmediate(final String actionId) {
		if (delayedAction != null && actionId.equals(delayedAction.getActionId())) {
			delayedAction.run();
			delayedAction = null;
		}
	}

	public void cancelAction(final String actionId) {
		if (delayedAction != null && actionId.equals(delayedAction.getActionId())) {
			delayedAction = null;
		}
	}

	public void scheduleAction(final String actionId, final int duration, final Runnable action) {
		delayedAction = new DelayAction(duration, actionId, action);
	}

	private void handlePing() {
		if (delayedAction != null && delayedAction.isReady()) {
			delayedAction.run();
			delayedAction = null;
		}
		if (holdAction.isRunning()) {
			holdAction.execute();
		}
		sections.forEach(MixControl::notifyBlink);
		host.scheduleTask(this::handlePing, 100);
	}

	private void initJogWheel() {
		final RelativeHardwareKnob fourDKnob = surface.createRelativeHardwareKnob("JOG_WHEEL");
		fourDKnob.setAdjustValueMatcher(midiIn.createRelativeSignedBitCCValueMatcher(0, 60, 128));
		fourDKnob.setStepSize(1 / 128.0);

		final HardwareActionBindable incAction = host.createAction(() -> jogWheelPlayPosition(1), () -> "+");
		final HardwareActionBindable decAction = host.createAction(() -> jogWheelPlayPosition(-1), () -> "-");
		mainLayer.bind(fourDKnob, host.createRelativeHardwareControlStepTarget(incAction, decAction));
	}

	private void jogWheelPlayPosition(final int dir) {
		double resolution = 0.25;
		if (modifier.isAltSet()) {
			resolution = 4.0;
		} else if (modifier.isShiftSet()) {
			resolution = 1.0;
		}
		changePlayPosition(dir, resolution, !modifier.isOptionSet(), !modifier.isControlSet());
	}

	private void changePlayPosition(final int inc, final double resolution, final boolean restrictToStart,
			final boolean quantize) {

		final double position = transport.playStartPosition().get();
		double newPos = position + resolution * inc;

		if (restrictToStart && newPos < 0) {
			newPos = 0;
		}

		if (position != newPos) {
			if (quantize) {
				final double intup = Math.floor(newPos / resolution);
				newPos = intup * resolution;
			}
			transport.playStartPosition().set(newPos);
			if (transport.isPlaying().get()) {
				transport.jumpToPlayStartPosition();
			}
		}
	}

	public VuMode getVuMode() {
		return vuMode;
	}

	private void initMasterSection() {
		masterTrack = getHost().createMasterTrack(8);
		masterTrack.volume().markInterested();
		final AbsoluteHardwareKnob masterFader = surface.createAbsoluteHardwareKnob("MASTER_FADER_");
		masterFader.setAdjustValueMatcher(midiIn.createAbsolutePitchBendValueMatcher(8));
		masterFader.addBinding(masterTrack.volume());
		final MotorFader masterFaderResponse = new MotorFader(midiOut, 8);
		mainLayer.addBinding(new FaderBinding(masterTrack.volume(), masterFaderResponse));

		final HardwareButton masterTouchButton = surface.createHardwareButton("MASTER_TOUCH");
		masterTouchButton.pressedAction()
				.setActionMatcher(midiIn.createNoteOnActionMatcher(0, NoteOnAssignment.TOUCH_VOLUME.getNoteNo() + 8));
		masterTouchButton.releasedAction()
				.setActionMatcher(midiIn.createNoteOffActionMatcher(0, NoteOnAssignment.TOUCH_VOLUME.getNoteNo() + 8));
		masterTouchButton.isPressed().addValueObserver(v -> {
			// RemoteConsole.out.println("TOUCHED MASTER {}", v);
		});
	}

	private void intiVPotModes() {
		createOnOfBoolButton(NoteOnAssignment.V_TRACK, trackChannelMode);
//		final Action[] allActions = application.getActions();
//		for (final Action action : allActions) {
//			RemoteConsole.out.println(" ACTION [{}] name={} id=[{}] ", action.getCategory().getName(), action.getName(),
//					action.getId());
//		}
		// createOnOfBoolButton(NoteOnAssignment.SOLO, soloExclusive);

		createModeButton(VPotMode.SEND);
		createModeButton(VPotMode.PAN);
		createModeButton(VPotMode.PLUGIN);
		createModeButton(VPotMode.EQ);
		createModeButton(VPotMode.INSTRUMENT);
	}

	private void initCursorSection() {
		final HardwareButton trackLeftButton = createPressButton(NoteOnAssignment.TRACK_LEFT);
		mainLayer.bindIsPressed(trackLeftButton, v -> navigateTrack(-1, v));
		final HardwareButton trackRightButton = createPressButton(NoteOnAssignment.TRACK_RIGHT);
		mainLayer.bindIsPressed(trackRightButton, v -> navigateTrack(1, v));
		final HardwareButton bankRightButton = createPressButton(NoteOnAssignment.BANK_RIGH);
		mainLayer.bindIsPressed(bankRightButton, v -> navigateBank(1, v));
		final HardwareButton bankLeftButton = createPressButton(NoteOnAssignment.BANK_LEFT);
		mainLayer.bindIsPressed(bankLeftButton, v -> navigateBank(-1, v));
		final HardwareButton upButton = createPressButton(NoteOnAssignment.CURSOR_UP);
		mainLayer.bindIsPressed(upButton, v -> navigateUpDown(1, v));
		final HardwareButton downButton = createPressButton(NoteOnAssignment.CURSOR_DOWN);
		mainLayer.bindIsPressed(downButton, v -> navigateUpDown(-1, v));
		final HardwareButton leftButton = createPressButton(NoteOnAssignment.CURSOR_LEFT);
		mainLayer.bindIsPressed(leftButton, v -> navigateLeftRight(-1, v));
		final HardwareButton rightButton = createPressButton(NoteOnAssignment.CURSOR_RIGHT);
		mainLayer.bindIsPressed(rightButton, v -> navigateLeftRight(1, v));
		createOnOfBoolButton(NoteOnAssignment.ZOOM, zoomActive);
		createOnOfBoolButton(NoteOnAssignment.SCRUB, scrubActive);
	}

	private void navigateTrack(final int direction, final boolean isPressed) {
		if (!isPressed) {
			return;
		}
		if (direction > 0) {
			mixerTrackBank.scrollForwards();
		} else {
			mixerTrackBank.scrollBackwards();
		}
	}

	private void navigateBank(final int direction, final boolean isPressed) {
		if (!isPressed) {
			return;
		}
		mixerTrackBank.scrollBy(direction * 8 * sections.size());
	}

	private void navigateUpDown(final int direction, final boolean isPressed) {
		if (!zoomActive.get()) {
			sections.forEach(section -> section.navigateUpDown(direction, isPressed));
		} else {
			if (!isPressed) {
				return;
			}
			if (direction > 0) {
				application.focusPanelAbove();
			} else {
				application.focusPanelBelow();
			}
		}
	}

	private void navigateLeftRight(final int direction, final boolean isPressed) {
		if (!zoomActive.get()) {
			sections.forEach(section -> section.navigateLeftRight(direction, isPressed));
		} else {
			if (!isPressed) {
				return;
			}
			if (modifier.isShiftSet()) {
				if (direction < 0) {
					application.zoomOut();
				} else {
					application.zoomIn();
				}

			} else {
				if (direction < 0) {
					application.focusPanelToLeft();
				} else {
					application.focusPanelToRight();
				}
			}
		}
	}

	private void initModifiers() {
		final HardwareButton shiftButton = createPressButton(NoteOnAssignment.SHIFT);
		shiftButton.isPressed().addValueObserver(v -> {
			modifier.setShift(v);
			if (v) {
				shiftLayer.activate();
			} else {
				shiftLayer.deactivate();
			}
		});
		final HardwareButton optionButton = createPressButton(NoteOnAssignment.OPTION);
		optionButton.isPressed().addValueObserver(v -> {
			modifier.setOption(v);
		});
		final HardwareButton controlButton = createPressButton(NoteOnAssignment.CONTROL);
		controlButton.isPressed().addValueObserver(v -> {
			modifier.setControl(v);
		});
		final HardwareButton altButton = createPressButton(NoteOnAssignment.ALT);
		altButton.isPressed().addValueObserver(v -> {
			modifier.setAlt(v);
		});
	}

	private void initTransport() {
		transport.playStartPosition().markInterested();
		final HardwareButton playButton = createButton(NoteOnAssignment.PLAY);
		final HardwareButton recordButton = createButton(NoteOnAssignment.RECORD);
		final HardwareButton stopButton = createButtonWState(NoteOnAssignment.STOP, transport.isPlaying(), true);
		final HardwareButton rewindButton = createHoldButton(NoteOnAssignment.REWIND);
		final HardwareButton fastForwardButton = createHoldButton(NoteOnAssignment.FFWD);

		final HardwareButton loopButton = createButton(NoteOnAssignment.CYCLE);
		mainLayer.bindToggle(loopButton, transport.isArrangerLoopEnabled());
		final HardwareButton metroButton = createButton(NoteOnAssignment.CLICK);
		mainLayer.bindToggle(metroButton, transport.isMetronomeEnabled());

		final HardwareButton autoWriteButton = createButton(NoteOnAssignment.AUTO_READ_OFF);
		mainLayer.bindPressed(autoWriteButton, transport.isArrangerAutomationWriteEnabled());
		mainLayer.bind(transport.isArrangerAutomationWriteEnabled(),
				(OnOffHardwareLight) autoWriteButton.backgroundLight());

		final HardwareButton touchButton = createButton(NoteOnAssignment.TOUCH);
		mainLayer.bindPressed(touchButton, () -> {
			transport.automationWriteMode().set("touch");
		});
		final HardwareButton latchButton = createButton(NoteOnAssignment.LATCH);
		mainLayer.bindPressed(latchButton, () -> {
			transport.automationWriteMode().set("latch");
		});
		final HardwareButton trimButton = createButton(NoteOnAssignment.AUTO_WRITE);
		mainLayer.bindPressed(trimButton, () -> {
			transport.automationWriteMode().set("write");
		});
		transport.automationWriteMode().addValueObserver(v -> {
			switch (v) {
			case "latch":
				setLed(latchButton, true);
				setLed(touchButton, false);
				setLed(trimButton, false);
				break;
			case "touch":
				setLed(latchButton, false);
				setLed(touchButton, true);
				setLed(trimButton, false);
				break;
			case "write":
				setLed(latchButton, false);
				setLed(touchButton, false);
				setLed(trimButton, true);
				break;
			}
		});

		final HardwareButton punchInButton = createButton(NoteOnAssignment.DROP);
		final HardwareButton punchOutButton = createButton(NoteOnAssignment.REPLACE);
		mainLayer.bindToggle(punchInButton, transport.isPunchInEnabled());
		mainLayer.bindToggle(punchOutButton, transport.isPunchOutEnabled());

		mainLayer.bindPressed(playButton, transport.continuePlaybackAction());
		mainLayer.bind(transport.isPlaying(), (OnOffHardwareLight) playButton.backgroundLight());
		mainLayer.bindPressed(stopButton, transport.stopAction());
		mainLayer.bindToggle(recordButton, transport.isArrangerRecordEnabled());

		mainLayer.bindIsPressed(fastForwardButton, pressed -> notifyHoldForwardReverse(pressed, 1));

		mainLayer.bindIsPressed(rewindButton, pressed -> notifyHoldForwardReverse(pressed, -1));

		final HardwareButton undoButton = createHoldButton(NoteOnAssignment.UNDO);
		mainLayer.bindIsPressed(undoButton, v -> {
			if (v) {
				if (!modifier.isShiftSet()) {
					application.undo();
					host.showPopupNotification("Undo");
				} else {
					application.redo();
					host.showPopupNotification("Redo");
				}
			}
		});
		transport.timeSignature().addValueObserver(sig -> {
			ledDisplay.setDivision(sig);
		});

		transport.playPosition()
				.addValueObserver(v -> ledDisplay.updatePosition(v, transport.playPosition().getFormatted()));
		transport.playPositionInSeconds().addValueObserver(ledDisplay::updateTime);

		createOnOfBoolButton(NoteOnAssignment.FLIP, flipped);

		final HardwareButton vuModeButton = createButton(NoteOnAssignment.DIPLAY_NAME);
		vuModeButton.isPressed().addValueObserver(v -> {
			if (v) {
				if (modifier.isShift()) {
					toogleVuMode();
				}
			}
		});

		final HardwareButton modeButton = createButton(NoteOnAssignment.DISPLAY_SMPTE);
		modeButton.isPressed().addValueObserver(v -> {
			if (v) {
				ledDisplay.toggleMode();
			}
		});
	}

	public void notifyHoldForwardReverse(final Boolean pressed, final int dir) {
		if (pressed) {
			holdAction.start(stage -> {
				if (modifier.isShiftSet()) {
					changePlayPosition(dir, FFWD_SPEEDS_SHIFT[Math.min(stage, FFWD_SPEEDS_SHIFT.length - 1)], true,
							true);
				} else {
					changePlayPosition(dir, FFWD_SPEEDS[Math.min(stage, FFWD_SPEEDS.length - 1)], true, true);
				}
			}, FFWD_TIMES);
		} else {
			holdAction.stop();
		}
	}

	public void createOnOfBoolButton(final NoteOnAssignment assignment, final SettableBooleanValue valueState) {
		final HardwareButton button = surface.createHardwareButton(assignment.toString() + "_BUTTON");
		assignment.holdActionAssign(midiIn, button);
		final OnOffHardwareLight led = surface.createOnOffHardwareLight(assignment.toString() + "_BUTTON_LED");
		button.setBackgroundLight(led);
		led.onUpdateHardware(() -> {
			sendLedUpdate(assignment, led.isOn().currentValue() ? 127 : 0);
		});
		mainLayer.bind(valueState, led);
		mainLayer.bindPressed(button, () -> {
			valueState.toggle();
		});
	}

	private void setLed(final HardwareButton button, final boolean onoff) {
		final OnOffHardwareLight light = (OnOffHardwareLight) button.backgroundLight();
		light.isOn().setValue(onoff);
	}

	private void toogleVuMode() {
		if (this.vuMode == VuMode.LED) {
			this.vuMode = VuMode.LED_LCD_VERTICAL;
		} else if (this.vuMode == VuMode.LED_LCD_VERTICAL) {
			this.vuMode = VuMode.LED_LCD_HORIZONTAL;
		} else {
			this.vuMode = VuMode.LED;
		}
		sections.forEach(section -> section.applyVuMode(this.getVuMode()));
	}

	private HardwareButton createHoldButton(final NoteOnAssignment assignment) {
		final HardwareButton button = surface.createHardwareButton(assignment + "_BUTTON");
		assignment.holdActionAssign(midiIn, button);

		final OnOffHardwareLight led = surface.createOnOffHardwareLight(assignment + "BUTTON_LED");
		button.setBackgroundLight(led);
		led.onUpdateHardware(() -> {
			sendLedUpdate(assignment, led.isOn().currentValue() ? 127 : 0);
		});
		button.isPressed().addValueObserver(v -> {
			led.isOn().setValue(v);
		});
		return button;
	}

	private HardwareButton createButtonWState(final NoteOnAssignment assignment,
			final SettableBooleanValue settableBooleanValue, final boolean reversedLed) {
		final HardwareButton button = surface.createHardwareButton(assignment + "_BUTTON");
		assignment.holdActionAssign(midiIn, button);
		final OnOffHardwareLight led = surface.createOnOffHardwareLight(assignment + "BUTTON_LED");
		button.setBackgroundLight(led);
		if (reversedLed) {
			led.onUpdateHardware(() -> sendLedUpdate(assignment, settableBooleanValue.get() ? 0 : 127));
		} else {
			led.onUpdateHardware(() -> sendLedUpdate(assignment, settableBooleanValue.get() ? 127 : 0));
		}
		settableBooleanValue.addValueObserver(v -> {
			led.isOn().setValue(!v);
		});
		return button;
	}

	private HardwareButton createModeButton(final VPotMode mode) {
		final HardwareButton button = surface.createHardwareButton(mode.getName() + "_BUTTON");
		mode.getButtonAssignment().holdActionAssign(midiIn, button);
		final OnOffHardwareLight led = surface.createOnOffHardwareLight(mode.getName() + "BUTTON_LED");
		button.setBackgroundLight(led);
		led.onUpdateHardware(() -> {
			sendLedUpdate(mode.getButtonAssignment(), led.isOn().currentValue() ? 127 : 0);
		});
		mainLayer.bindPressed(button, () -> setVPotMode(mode, true));
		mainLayer.bindReleased(button, () -> {
			setVPotMode(mode, false);
		});
		led.isOn().setValueSupplier(() -> {
			return this.trackChannelMode.getMode() == mode;
		});
		return button;
	}

	public void setVPotMode(final VPotMode mode, final boolean down) {
		if (this.trackChannelMode.getMode() == mode) {
			sections.forEach(control -> control.notifyModeAdvance(down));
		} else {
			this.trackChannelMode.setMode(mode);
			sections.forEach(section -> section.notifyModeChange(mode, down));
		}
	}

	public VPotMode getVpotMode() {
		return this.trackChannelMode.getMode();
	}

	private HardwareButton createPressButton(final NoteOnAssignment assignment) {
		final HardwareButton button = surface.createHardwareButton(assignment.toString() + "_BUTTON");
		assignment.holdActionAssign(midiIn, button);
		button.setBackgroundLight(surface.createOnOffHardwareLight(assignment.toString() + "_BUTTON_LED"));
		return button;
	}

	private HardwareButton createButton(final NoteOnAssignment assignment) {
		final HardwareButton button = surface.createHardwareButton(assignment + "_BUTTON");
		assignment.holdActionAssign(midiIn, button);
		final OnOffHardwareLight led = surface.createOnOffHardwareLight(assignment + "_BUTTON_LED");
		button.setBackgroundLight(led);
		led.onUpdateHardware(() -> {
			sendLedUpdate(assignment, led.isOn().currentValue() ? 127 : 0);
		});
		return button;
	}

	private void onMidi0(final ShortMidiMessage msg) {
		RemoteConsole.out.println(" MIDI ch={} st={} d1={} d2={}", msg.getChannel(), msg.getStatusByte(),
				msg.getData1(), msg.getData2());
	}

	private void setUpMidiSysExCommands() {
		midiIn.setSysexCallback(data -> {
			if (data.startsWith(SYSEX_DEVICE_RELOAD)) {
				updateAll(data);
			} else {
//				RemoteConsole.out.println(" MIDI SYS EX {}", data);
			}
		});
	}

	private void updateAll(final String command) {
		surface.updateHardware();
		sections.forEach(MixControl::fullHardwareUpdate);
		for (int i = 0; i < lightStatusMap.length; i++) {
			if (lightStatusMap[i] >= 0) {
				midiOut.sendMidi(Midi.NOTE_ON, i, lightStatusMap[i]);
			}
		}
	}

	protected void initTrackBank(final int nrOfScenes) {
		// initNaviagtion();

		cursorTrack = getHost().createCursorTrack(8, nrOfScenes);
		cursorTrack.color().markInterested();

		final HardwareButton soloButton = createButtonWState(NoteOnAssignment.SOLO, cursorTrack.solo(), false);
		mainLayer.bindPressed(soloButton, cursorTrack.solo().toggleAction());

		this.cursorDevice = cursorTrack.createCursorDevice();

		final TrackBank mainTrackBank = getHost().createMainTrackBank(8 * sections.size(), 1, nrOfScenes);

		mainTrackBank.followCursorTrack(cursorTrack);

		mixerTrackBank = getHost().createMainTrackBank(8 * sections.size(), 1, nrOfScenes);
		mixerTrackBank.setSkipDisabledItems(false);
		mixerTrackBank.canScrollChannelsDown().markInterested();
		mixerTrackBank.canScrollChannelsUp().markInterested();

		globalTrackBank = host.createTrackBank(8 * sections.size(), 1, nrOfScenes);
		globalTrackBank.setSkipDisabledItems(false);
		globalTrackBank.canScrollChannelsDown().markInterested();
		globalTrackBank.canScrollChannelsUp().markInterested();

		final DeviceMatcher eq5Matcher = host.createBitwigDeviceMatcher(Devices.EQ_PLUS.getUuid());
		eqDevice = new EqDevice(this, eq5Matcher);

		final DeviceMatcher notEq = host.createNotDeviceMatcher(eq5Matcher);
		final DeviceMatcher combinedMatcher = host.createAndDeviceMatcher(notEq, host.createAudioEffectMatcher());

		instrumentDevice = new DeviceTracker(this, "Instrument", host.createInstrumentMatcher(), false);
		pluginDevice = new DeviceTracker(this, "Audio-FX", combinedMatcher, true);

		for (final MixControl channelSection : sections) {
			channelSection.initMainControl(mixerTrackBank, globalTrackBank);
		}
		mainSection.initTrackControl(cursorTrack, instrumentDevice, pluginDevice, eqDevice);
		initMenuButtons();
	}

	private void initMenuButtons() {
		final HardwareButton markerButton = createButton(NoteOnAssignment.MARKER);
		final BooleanValueObject marker = new BooleanValueObject();

		final BeatTimeFormatter formatter = host.createBeatTimeFormatter(":", 2, 1, 1, 0);

		final MenuModeLayerConfiguration markerMenuConfig = new MenuModeLayerConfiguration("MARKER_MENU", mainSection);
		final CueMarkerBank cueMarkerBank = arranger.createCueMarkerBank(8);
		for (int i = 0; i < 8; i++) {
			final CueMarker cueMarker = cueMarkerBank.getItemAt(i);
			cueMarker.exists().markInterested();
			markerMenuConfig.addValueBinding(i, cueMarker.position(), cueMarker, "---", v -> {
				return cueMarker.position().getFormatted(formatter);
			});
			markerMenuConfig.addNameBinding(i, cueMarker.getName(), cueMarker, "<Cue" + (i + 1) + ">");
			markerMenuConfig.addRingExistsBinding(i, cueMarker);
			markerMenuConfig.addPressEncoderBinding(i, index -> {
				if (cueMarker.exists().get()) {
					cueMarker.position().set(transport.getPosition().get());
				}
			});
			markerMenuConfig.addEncoderIncBinding(i, cueMarker.position(), 1);
		}

		mainLayer.bind(marker, (OnOffHardwareLight) markerButton.backgroundLight());
		mainLayer.bindPressed(markerButton, () -> {
			holdState.enter(mainSection.getCurrentConfiguration(), markerButton.getName());
			mainSection.setConfiguration(markerMenuConfig);
		});
		mainLayer.bindReleased(markerButton, () -> {
			final LayerConfiguration layer = holdState.endHold();
			if (layer != null) {
				mainSection.setConfiguration(layer);
			}
			if (holdState.exit()) {
				marker.toggle();
			}
		});

		createOnOfBoolButton(NoteOnAssignment.GLOBAL_VIEW, globalViewActive);
		createOnOfBoolButton(NoteOnAssignment.GROUP, groupViewActive);

		initFButton(0, NoteOnAssignment.F1, marker, cueMarkerBank, () -> transport.returnToArrangement());
		initFButton(1, NoteOnAssignment.F2, marker, cueMarkerBank,
				() -> application.setPanelLayout(currentLayoutType.other().getName()));
		initFButton(2, NoteOnAssignment.F3, marker, cueMarkerBank, () -> {
		});
		initFButton(3, NoteOnAssignment.F4, marker, cueMarkerBank, () -> {
		});
		initFButton(4, NoteOnAssignment.F5, marker, cueMarkerBank, () -> {
		});
		initFButton(5, NoteOnAssignment.F6, marker, cueMarkerBank, () -> {
		});
		initFButton(6, NoteOnAssignment.F7, marker, cueMarkerBank, () -> {
		});
		initFButton(7, NoteOnAssignment.F8, marker, cueMarkerBank, () -> {
		});

		application.panelLayout().addValueObserver(v -> {
			currentLayoutType = LayoutType.toType(v);
		});
	}

	public void initFButton(final int index, final NoteOnAssignment assign, final BooleanValueObject marker,
			final CueMarkerBank cueMarkerBank, final Runnable nonMarkerFunction) {
		final HardwareButton fButton = createPressButton(assign);
		mainLayer.bindIsPressed(fButton, v -> {
			if (v) {
				if (marker.get()) {
					cueMarkerBank.getItemAt(index).launch(modifier.isShift());
				} else {
					nonMarkerFunction.run();
				}
			}
		});
	}

	public BooleanValueObject getGlobalViewActive() {
		return globalViewActive;
	}

	public BooleanValueObject getGroupViewActive() {
		return groupViewActive;
	}

	public DeviceTracker getPluginDevice() {
		return pluginDevice;
	}

	public EqDevice getEqDevice() {
		return eqDevice;
	}

	public DeviceTracker getInstrumentDevice() {
		return instrumentDevice;
	}

	public PinnableCursorDevice getCursorDevice() {
		return cursorDevice;
	}

	public CursorTrack getCursorTrack() {
		return cursorTrack;
	}

	public void sendLedUpdate(final NoteOnAssignment assingment, final int value) {
		final int noteNr = assingment.getNoteNo();
		lightStatusMap[noteNr] = value;
		midiOut.sendMidi(assingment.getType(), assingment.getNoteNo(), value);
	}

	public Layer getMainLayer() {
		return mainLayer;
	}

	private boolean shutdownHook = false;

	@Override
	public void exit() {
		shutdownHook = true;
		final Thread shutdown = new Thread(() -> {
			ledDisplay.clearAll();
			sections.forEach(MixControl::resetLeds);
			sections.forEach(MixControl::resetFaders);
			sections.forEach(MixControl::exitMessage);
			try {
				Thread.sleep(300);
			} catch (final InterruptedException e) {
			}
			shutdownHook = false;
		});
		shutdown.start();
		while (shutdownHook) {
			try {
				Thread.sleep(1000);
			} catch (final InterruptedException e) {
			}
		}
		getHost().showPopupNotification(" Exit Mackie MCU Pro");
	}

	public Layers getLayers() {
		return layers;
	}

	public MidiIn getMidiIn() {
		return midiIn;
	}

	public HardwareSurface getSurface() {
		return surface;
	}

	@Override
	public void flush() {
		surface.updateHardware();
	}

	public Project getProject() {
		return project;
	}

	public Application getApplication() {
		return application;
	}

	public ModifierValueObject getModifier() {
		return modifier;
	}

	public BooleanValueObject getFlipped() {
		return flipped;
	}

	public TrackModeValue getTrackChannelMode() {
		return trackChannelMode;
	}
}
