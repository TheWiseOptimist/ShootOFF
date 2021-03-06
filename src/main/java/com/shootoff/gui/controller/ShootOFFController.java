/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.gui.controller;

import java.awt.Toolkit;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.openimaj.util.parallel.GlobalExecutorPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.Closeable;
import com.shootoff.Main;
import com.shootoff.camera.Camera;
import com.shootoff.camera.CameraErrorView;
import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.config.Configuration;
import com.shootoff.courses.Course;
import com.shootoff.courses.io.CourseIO;
import com.shootoff.gui.CalibrationConfigurator;
import com.shootoff.gui.CalibrationManager;
import com.shootoff.gui.CalibrationOption;
import com.shootoff.gui.CameraConfigListener;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.ExerciseListener;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.Resetter;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.pane.ExerciseSlide;
import com.shootoff.gui.pane.FileSlide;
import com.shootoff.gui.pane.ShotSectorPane;
import com.shootoff.gui.pane.TargetSlide;
import com.shootoff.plugins.ProjectorTrainingExerciseBase;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.TrainingExerciseBase;
import com.shootoff.plugins.engine.Plugin;
import com.shootoff.plugins.engine.PluginEngine;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.io.SessionIO;
import com.shootoff.targets.Target;
import com.shootoff.targets.CameraViews;
import com.shootoff.targets.TargetRegion;
import com.shootoff.util.TimerPool;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Shape;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class ShootOFFController implements CameraConfigListener, CameraErrorView, CameraViews, CalibrationConfigurator, Closeable, Resetter, ExerciseListener {
	private Stage shootOFFStage;
	@FXML private HBox controlsContainer;
	@FXML private VBox bodyContainer;
	@FXML private ContextMenu projectorContextMenu;
	@FXML private MenuItem toggleSessionRecordingMenuItem;
	@FXML private MenuItem showSessionViewerMenuItem;
	@FXML private ToggleGroup trainingToggleGroup;
	@FXML private TabPane cameraTabPane;
	@FXML private TableView<ShotEntry> shotTimerTable;
	@FXML private MenuItem startArenaMenuItem;
	@FXML private MenuItem toggleArenaCalibrationMenuItem;
	@FXML private Menu calibrationOptionsMenu;
	@FXML private ToggleGroup calibrationToggleGroup;
	@FXML private Menu addArenaTargetMenu;
	@FXML private MenuItem clearArenaTargetsMenuItem;
	@FXML private Menu arenaBackgroundMenu;
	@FXML private Menu coursesMenu;
	@FXML private MenuItem toggleArenaShotsMenuItem;
	@FXML private VBox buttonsContainer;
	@FXML private HBox trainingExerciseContainer;

	private TargetSlide targetPane;
	private ExerciseSlide exerciseSlide;
	
	private String defaultWindowTitle;
	private CamerasSupervisor camerasSupervisor;
	private Configuration config;
	private PluginEngine pluginEngine;
	private static final Logger logger = LoggerFactory.getLogger(ShootOFFController.class);
	private final ObservableList<ShotEntry> shotEntries = FXCollections.observableArrayList();
	private final List<Stage> streamDebuggerStages = new ArrayList<Stage>();

	private ProjectorArenaController arenaController;
	private Optional<CalibrationManager> calibrationManager = Optional.empty();
	
	static public double getDpiScaleFactorForScreen() {
		//http://news.kynosarges.org/2015/06/29/javafx-dpi-scaling-fixed/
		// Number of actual horizontal lines (768p)
		final double trueHorizontalLines = Toolkit.getDefaultToolkit().getScreenSize().getHeight();
		// Number of scaled horizontal lines. (384p for 200%)
		final double scaledHorizontalLines = Screen.getPrimary().getBounds().getHeight();
		// DPI scale factor.
		final double dpiScaleFactor = trueHorizontalLines / scaledHorizontalLines;
		
		return dpiScaleFactor;
	}

	public void init(Configuration config) throws IOException {
		this.config = config;
		this.camerasSupervisor = new CamerasSupervisor(config);

		targetPane = new TargetSlide(controlsContainer, bodyContainer, this);
		
		exerciseSlide = new ExerciseSlide(controlsContainer, bodyContainer, this);

		pluginEngine = new PluginEngine(exerciseSlide);

		
		initDefaultBackgrounds();
		pluginEngine.startWatching();

		shootOFFStage = (Stage) controlsContainer
				.getScene()
				.getWindow();
		this.defaultWindowTitle = shootOFFStage.getTitle();
		shootOFFStage.getIcons().addAll(
				new Image(ShootOFFController.class.getResourceAsStream("/images/icon_16x16.png")),
				new Image(ShootOFFController.class.getResourceAsStream("/images/icon_32x32.png")),
				new Image(ShootOFFController.class.getResourceAsStream("/images/icon_48x48.png")),
				new Image(ShootOFFController.class.getResourceAsStream("/images/icon_64x64.png")),
				new Image(ShootOFFController.class.getResourceAsStream("/images/icon_128x128.png")),
				new Image(ShootOFFController.class.getResourceAsStream("/images/icon_256x256.png")));

		shootOFFStage.setOnCloseRequest((value) -> {
			close();
		});

		if (config.getWebcams().isEmpty()) {
			Optional<Camera> defaultCamera = Camera.getDefault();
			if (defaultCamera.isPresent()) {
				if (!addCameraTab("Default", defaultCamera.get())) {
					// Failed to open the default camera. This sometimes happens
					// on Windows when video devices get registered and set as
					// the default camera even though the physical device is not
					// actually present. This seems to happen sometimes with TV
					// tuners and buggy camera drivers. As a workaround, try to
					// fall back to using a different camera as the default.
					List<Camera> allCameras = Camera.getWebcams();

					if (allCameras.size() <= 1) {
						showCameraLockError(defaultCamera.get(), true);
					} else {
						for (Camera c : allCameras) {
							if (!c.equals(defaultCamera.get())) {
								if (!addCameraTab("Default", c)) {
									showCameraLockError(c, true);
								}

								break;
							}
						}
					}
				}
			} else {
				Main.closeNoCamera();
			}
		} else {
			addConfiguredCameras();
		}

		TableColumn<ShotEntry, String> timeCol = new TableColumn<ShotEntry, String>("Time");
		timeCol.setMinWidth(85);
		timeCol.setCellValueFactory(new PropertyValueFactory<ShotEntry, String>("timestamp"));

		TableColumn<ShotEntry, ShotEntry.SplitData> splitCol = new TableColumn<ShotEntry, ShotEntry.SplitData>("Split");
		splitCol.setMinWidth(85);
		splitCol.setCellValueFactory(new PropertyValueFactory<ShotEntry, ShotEntry.SplitData>("split"));

		TableColumn<ShotEntry, String> laserCol = new TableColumn<ShotEntry, String>("Laser");
		laserCol.setMinWidth(85);
		laserCol.setCellValueFactory(new PropertyValueFactory<ShotEntry, String>("color"));

		shotEntries.addListener(new ListChangeListener<ShotEntry>() {
			@Override
			public void onChanged(Change<? extends ShotEntry> change) {
				change.next();
				if (change.getAddedSize() < 1) return;
				Platform.runLater(() -> {
					final int size = shotTimerTable.getItems().size();
					if (size > 0) shotTimerTable.scrollTo(size - 1);
				});
			}
		});

		calibrationToggleGroup.selectedToggleProperty().addListener(new ChangeListener<Toggle>() {
			public void changed(ObservableValue<? extends Toggle> ov, Toggle oldToggle, Toggle newToggle) {
				if (newToggle == null) return;

				if (calibrationManager.isPresent())
					calibrationManager.get().configureArenaCamera(getSelectedCalibrationOption());
			}
		});

		shotTimerTable.getSelectionModel().getSelectedItems().addListener(new ListChangeListener<ShotEntry>() {
			@Override
			public void onChanged(Change<? extends ShotEntry> change) {
				while (change.next()) {
					for (ShotEntry unselected : change.getRemoved()) {
						unselected.getShot().getMarker().setFill(unselected.getShot().getColor());
					}

					for (ShotEntry selected : change.getAddedSubList()) {
						selected.getShot().getMarker().setFill(TargetRegion.SELECTED_STROKE_COLOR);

						// Move all selected shots to top the of their z-stack
						// to ensure visibility
						for (CameraView cv : camerasSupervisor.getCameraViews()) {
							CanvasManager cm = (CanvasManager) cv;

							Shape marker = selected.getShot().getMarker();
							if (cm.getCanvasGroup().getChildren()
									.indexOf(marker) < cm.getCanvasGroup().getChildren().size() - 1) {
								cm.getCanvasGroup().getChildren().remove(marker);
								cm.getCanvasGroup().getChildren().add(cm.getCanvasGroup().getChildren().size(), marker);
							}
						}
					}
				}
			}
		});

		shotTimerTable.setRowFactory(tableView -> new TableRow<ShotEntry>() {
			@Override
			protected void updateItem(ShotEntry item, boolean empty) {
				super.updateItem(item, empty);

				if (item == null || empty) {
					setStyle("");
					return;
				}

				if (item.getRowColor().isPresent()) {
					setStyle("-fx-background-color: " + CanvasManager.colorToWebCode(item.getRowColor().get()));
				} else {
					setStyle("");
				}
			}
		});

		splitCol.setCellFactory(column -> {
			return new TableCell<ShotEntry, ShotEntry.SplitData>() {
				@Override
				public void updateItem(ShotEntry.SplitData item, boolean empty) {
					super.updateItem(item, empty);

					if (item == null || empty) {
						setText(null);
						setStyle("");
						return;
					}

					setText(item.getSplit());

					if (item.hadMalfunction()) {
						setStyle("-fx-background-color: orange");
					} else if (item.hadReload()) {
						setStyle("-fx-background-color: lightskyblue");
					} else if (item.getRowColor().isPresent()) {
						setStyle("-fx-background-color: " + CanvasManager.colorToWebCode(item.getRowColor().get()));
					} else {
						setStyle("");
					}
				}
			};
		});

		shotTimerTable.getColumns().add(timeCol);
		shotTimerTable.getColumns().add(splitCol);
		shotTimerTable.getColumns().add(laserCol);
		shotTimerTable.setItems(shotEntries);
		shotTimerTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
	}

	@Override
	public void close() {
		shootOFFStage.close();
		camerasSupervisor.closeAll();
		pluginEngine.stopWatching();

		if (config.getExercise().isPresent()) config.getExercise().get().destroy();

		if (arenaController != null) {
			arenaController.getCanvasManager().close();
			arenaController.close();
		}

		for (Stage streamDebuggerStage : streamDebuggerStages) {
			streamDebuggerStage.close();
		}

		if (config.getSessionRecorder().isPresent()) {
			toggleSessionRecordingMenuItem.fire();
		}

		TimerPool.close();
		GlobalExecutorPool.getPool().shutdownNow();

		if (!config.getVideoPlayers().isEmpty()) {
			for (VideoPlayerController videoPlayer : config.getVideoPlayers()) {
				videoPlayer.getStage().close();
			}
		}

		if (!config.inDebugMode()) Main.forceClose(0);
	}

	@Override
	public CalibrationOption getSelectedCalibrationOption() {
		if (calibrationToggleGroup == null) return CalibrationOption.EVERYWHERE;

		Toggle selectedToggle = calibrationToggleGroup.getSelectedToggle();
		if (selectedToggle != null && selectedToggle instanceof RadioMenuItem) {
			RadioMenuItem selectedOption = (RadioMenuItem) calibrationToggleGroup.getSelectedToggle();

			switch (selectedOption.getText().toLowerCase(Locale.getDefault())) {
			case "detect everywhere":
				return CalibrationOption.EVERYWHERE;

			case "only detect in projector bounds":
				return CalibrationOption.ONLY_IN_BOUNDS;

			case "crop feed to projector bounds":
				return CalibrationOption.CROP;

			default:
				logger.error("Unknown calibration option, defaulting to only in projection bounds: {}",
						selectedOption.getText());

				return CalibrationOption.ONLY_IN_BOUNDS;
			}
		} else {
			logger.error("No calibration toggle selected or it's not a RadioMenuItem. This should not be possible.");
			return CalibrationOption.ONLY_IN_BOUNDS;
		}
	}
	
	@Override
	public CameraView getSelectedCameraView() {
		return camerasSupervisor.getCameraView(cameraTabPane.getSelectionModel().getSelectedIndex());
	}

	@Override
	public CameraManager getSelectedCameraManager() {
		return camerasSupervisor.getCameraManager(cameraTabPane.getSelectionModel().getSelectedIndex());
	}
	
	@Override 
	public Node getSelectedCameraContainer() {
		return cameraTabPane.getSelectionModel().getSelectedItem().getContent();
	}
	
	public Stage getStage() {
		return shootOFFStage;
	}

	public VBox getButtonsPane() {
		return buttonsContainer;
	}
	
	public HBox getTrainingExerciseContainer() {
		return trainingExerciseContainer;
	}

	public TableView<ShotEntry> getShotEntryTable() {
		return shotTimerTable;
	}

	@Override
	public void cameraConfigUpdated() {
		config.unregisterAllRecordingCameraManagers();
		addConfiguredCameras();
	}
	
	@Override
	public Configuration getConfiguration() {
		return config;
	}

	private void addConfiguredCameras() {
		cameraTabPane.getTabs().clear();
		camerasSupervisor.clearManagers();

		if (config.getWebcams().isEmpty()) {
			Optional<Camera> defaultCam = Camera.getDefault();

			if (defaultCam.isPresent()) {
				if (!addCameraTab("Default", defaultCam.get())) showCameraLockError(defaultCam.get(), true);
			} else {
				logger.error("Default camera was not fetched after clearing camera settings!");
				Main.closeNoCamera();
			}
		} else {
			int failureCount = 0;

			for (String webcamName : config.getWebcams().keySet()) {
				Camera webcam = config.getWebcams().get(webcamName);

				if (!addCameraTab(webcamName, webcam)) {
					failureCount++;
					showCameraLockError(webcam, failureCount == config.getWebcams().size());
				}
			}
		}
	}

	private boolean addCameraTab(String webcamName, Camera webcam) {
		if (webcam.isLocked() && !webcam.isOpen()) {
			return false;
		}

		// We want the CameraManager to configure the camera, we just try to
		// open and close it here to see if we can. If we hold off on doing this
		// until later it's harder to give the user a good error message.
		String os = System.getProperty("os.name");
		if (os != null && !os.equals("Mac OS X")) {
			if (!webcam.isOpen() && !webcam.open()) {
				return false;
			} else {
				webcam.close();
			}
		}

		Tab cameraTab = new Tab(webcamName);
		Group cameraCanvasGroup = new Group();
		// 640 x 480
		cameraTab.setContent(new AnchorPane(cameraCanvasGroup));

		CanvasManager canvasManager = new CanvasManager(cameraCanvasGroup, config, this, webcamName, shotEntries);
		CameraManager cameraManager = camerasSupervisor.addCameraManager(webcam, this, canvasManager);

		if (config.getRecordingCameras().contains(webcam)) {
			config.registerRecordingCameraManager(cameraManager);
		}

		canvasManager.setContextMenu(createContextMenu());

		// Show coords of mouse when in canvas during debug mode
		if (config.inDebugMode()) {
			canvasManager.getCanvasGroup().setOnMouseMoved((event) -> {
				shootOFFStage.setTitle(defaultWindowTitle + String.format(" (%.1f, %.1f)", event.getX(), event.getY()));
			});

			canvasManager.getCanvasGroup().setOnMouseExited((event) -> {
				shootOFFStage.setTitle(defaultWindowTitle);
			});
		}

		return cameraTabPane.getTabs().add(cameraTab);
	}

	private ContextMenu createContextMenu() {
		ContextMenu contextMenu = new ContextMenu();

		MenuItem toggleDetectionSectors = new MenuItem("Toggle Shot Detection Sectors");

		toggleDetectionSectors.setOnAction((event) -> {
			AnchorPane tabAnchor = (AnchorPane) cameraTabPane.getSelectionModel().getSelectedItem().getContent();

			// Only add the pane if it isn't already open
			boolean hasPane = false;
			for (Node node : tabAnchor.getChildren()) {
				if (node instanceof ShotSectorPane) {
					hasPane = true;
					break;
				}
			}

			if (!hasPane) {
				CameraManager cameraManager = camerasSupervisor
						.getCameraManager(cameraTabPane.getSelectionModel().getSelectedIndex());
				new ShotSectorPane(tabAnchor, cameraManager);
			}
		});

		contextMenu.getItems().add(toggleDetectionSectors);

		final String os = System.getProperty("os.name");
		
		if (os != null && os.startsWith("Windows"))
		{
			final MenuItem cameraMenuItem = new MenuItem("Configure Camera");

			cameraMenuItem.setOnAction((event) -> {
				CameraManager cameraManager = camerasSupervisor
						.getCameraManager(cameraTabPane.getSelectionModel().getSelectedIndex());

				cameraManager.launchCameraSettings();
			});

			contextMenu.getItems().add(cameraMenuItem);
		}
		
		if (config.inDebugMode()) {
			MenuItem startStreamDebuggerMenuItem = new MenuItem("Start Stream Debugger");

			startStreamDebuggerMenuItem.setOnAction((event) -> {
				FXMLLoader loader = new FXMLLoader(
						getClass().getClassLoader().getResource("com/shootoff/gui/StreamDebugger.fxml"));
				try {
					loader.load();
				} catch (Exception e) {
					logger.error("Error loading StreamDebugger FXML file", e);
				}

				Stage streamDebuggerStage = new Stage();
				streamDebuggerStages.add(streamDebuggerStage);

				String tabName = cameraTabPane.getSelectionModel().getSelectedItem().getText();
				streamDebuggerStage.setTitle(String.format("Stream Debugger -- %s", tabName));
				streamDebuggerStage.setScene(new Scene(loader.getRoot()));
				streamDebuggerStage.show();
				CameraManager cameraManager = camerasSupervisor
						.getCameraManager(cameraTabPane.getSelectionModel().getSelectedIndex());
				((StreamDebuggerController) loader.getController()).init(cameraManager);

				startStreamDebuggerMenuItem.setDisable(true);

				streamDebuggerStage.setOnCloseRequest((e) -> {
					startStreamDebuggerMenuItem.setDisable(false);
					cameraManager.setThresholdListener(null);
					streamDebuggerStages.remove(streamDebuggerStage);
				});
			});

			contextMenu.getItems().add(startStreamDebuggerMenuItem);

			MenuItem recordMenuItem = new MenuItem("Start Recording");

			recordMenuItem.setOnAction((event) -> {
				CameraManager cameraManager = camerasSupervisor
						.getCameraManager(cameraTabPane.getSelectionModel().getSelectedIndex());

				if (recordMenuItem.getText().equals("Start Recording")) {
					recordMenuItem.setText("Stop Recording");

					String tabName = cameraTabPane.getSelectionModel().getSelectedItem().getText();
					String videoName = tabName + ".mp4";
					cameraManager.startRecordingStream(new File(videoName));
				} else {
					recordMenuItem.setText("Start Recording");
					cameraManager.stopRecordingStream();
				}
			});

			contextMenu.getItems().add(recordMenuItem);
		}

		return contextMenu;
	}

	@Override
	public List<Target> getTargets() {
		final List<Target> targets = new ArrayList<Target>();

		for (final CameraManager manager : camerasSupervisor.getCameraManagers()) {
			targets.addAll(((CanvasManager) manager.getCameraView()).getTargets());
		}

		return targets;
	}
	
	@FXML
	public void fileButtonClicked(MouseEvent event) {
		new FileSlide(controlsContainer, bodyContainer, this, this, this).showControls();
	}
	
	@FXML
	public void targetsButtonClicked(MouseEvent event) {
		targetPane.showControls();
	}
	
	@FXML
	public void trainingButtonClicked(MouseEvent event) {
		exerciseSlide.showControls();
	}

	@FXML
	public void toggleSessionRecordingMenuItemClicked(ActionEvent event) {
		if (config.getSessionRecorder().isPresent()) {
			for (CameraManager cm : config.getRecordingManagers()) {
				cm.stopRecordingShots();
			}

			SessionIO.saveSession(config.getSessionRecorder().get(), new File(System.getProperty("shootoff.home")
					+ File.separator + "sessions/" + config.getSessionRecorder().get().getSessionName() + ".xml"));

			config.setSessionRecorder(null);

			toggleSessionRecordingMenuItem.setText("Record Session");
		} else {
			config.setSessionRecorder(new SessionRecorder());

			for (CameraManager cm : config.getRecordingManagers()) {
				cm.startRecordingShots();
			}

			toggleSessionRecordingMenuItem.setText("Stop Recording");
		}
	}
	
	@FXML
	public void projectorButtonClicked(MouseEvent event) {
		projectorContextMenu.show((Node) event.getSource(), event.getScreenX(), event.getScreenY());
	}

	@FXML
	public void startArenaClicked(ActionEvent event) throws IOException {
		toggleProjectorMenus(false);
		startArenaMenuItem.setDisable(true);

		if (arenaController == null) {
			FXMLLoader loader = new FXMLLoader(
					getClass().getClassLoader().getResource("com/shootoff/gui/ProjectorArena.fxml"));
			loader.load();

			Stage arenaStage = new Stage();

			arenaStage.setTitle("Projector Arena");
			arenaStage.setScene(new Scene(loader.getRoot()));

			arenaController = (ProjectorArenaController) loader.getController();
			CameraManager calibratingCameraManager = camerasSupervisor
					.getCameraManager(cameraTabPane.getSelectionModel().getSelectedIndex());
			arenaController.init(this.getStage(), config, this);
			calibrationManager = Optional.of(new CalibrationManager(this, calibratingCameraManager, arenaController));
			arenaController.setCalibrationManager(calibrationManager.get());
			arenaController.getCanvasManager().setShowShots(false);

			arenaStage.setOnCloseRequest((e) -> {
				if (config.getExercise().isPresent()
						&& config.getExercise().get() instanceof ProjectorTrainingExerciseBase) {
					exerciseSlide.disableProjectorExercises();
				}
				toggleArenaShotsMenuItem.setText("Show Shot Markers");
				if (calibrationManager.isPresent()) {
					if (calibrationManager.get().isCalibrating()) {
						calibrationManager.get().stopCalibration();
					} else {
						calibrationManager.get().arenaClosing();
					}
				}
				toggleProjectorMenus(true);
				startArenaMenuItem.setDisable(false);
				arenaController.setFeedCanvasManager(null);
				arenaController = null;
			});
		}

		arenaController.toggleArena();
		arenaController.autoPlaceArena();

		toggleArenaCalibrationMenuItem.fire();
	}

	private void toggleProjectorMenus(boolean isDisabled) {
		toggleArenaCalibrationMenuItem.setDisable(isDisabled);
		calibrationOptionsMenu.setDisable(isDisabled);
		addArenaTargetMenu.setDisable(isDisabled);
		clearArenaTargetsMenuItem.setDisable(isDisabled);
		arenaBackgroundMenu.setDisable(isDisabled);
		coursesMenu.setDisable(isDisabled);
		toggleArenaShotsMenuItem.setDisable(isDisabled);

		// TODO: Implement in ExerciseSlide
		//for (MenuItem m : projectorExerciseMenuItems)
		//	m.setDisable(isDisabled);
	}

	@Override
	public void toggleCalibrating() {
		final Runnable toggleCalibrationAction = () -> {
			if (toggleArenaCalibrationMenuItem.getText().equals("Calibrate"))
				toggleArenaCalibrationMenuItem.setText("Stop Calibrating");
			else
				toggleArenaCalibrationMenuItem.setText("Calibrate");
		};

		if (Platform.isFxApplicationThread()) {
			toggleCalibrationAction.run();
		} else {
			Platform.runLater(toggleCalibrationAction);
		}
	}

	@FXML
	public void toggleArenaCalibrationClicked(ActionEvent event) {
		if (!calibrationManager.isPresent()) return;

		if (!calibrationManager.get().isCalibrating()) {
			calibrationManager.get().enableCalibration();
		} else {
			calibrationManager.get().stopCalibration();
		}
	}

	private void initDefaultBackgrounds() {
		addDefaultBackground("Hickok45 Autumn", "/arena/backgrounds/hickok45_autumn.gif");
		addDefaultBackground("Hickok45 Summer", "/arena/backgrounds/hickok45_summer.gif");
		addDefaultBackground("Indoor Range", "/arena/backgrounds/indoor_range.gif");
		addDefaultBackground("Kiang West Savanna", "/arena/backgrounds/kiang_west_savanna.gif");
		addDefaultBackground("Outdoor Range", "/arena/backgrounds/outdoor_range.gif");
		addDefaultBackground("Steel Range Bay", "/arena/backgrounds/steel_range_bay.gif");
	}

	private void addDefaultBackground(String menuName, String resourceName) {
		MenuItem backgroundMenuItem = new MenuItem(menuName);

		backgroundMenuItem.setOnAction((e) -> {
			InputStream is = this.getClass().getResourceAsStream(resourceName);
			LocatedImage img = new LocatedImage(is, resourceName);
			arenaController.setBackground(img);
		});

		arenaBackgroundMenu.getItems().add(backgroundMenuItem);
	}

	@FXML
	public void clearArenaTargetsMenuItemClicked(ActionEvent event) {
		arenaController.getCanvasManager().clearTargets();
	}

	@FXML
	public void removeArenaBackgroundMenuItemClicked(ActionEvent event) {
		arenaController.setBackground(null);
	}

	@FXML
	public void openArenaBackgroundMenuItemClicked(ActionEvent event) {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Select Arena Background");
		fileChooser.getExtensionFilters().addAll(
				new FileChooser.ExtensionFilter("Portable Network Graphic (*.png)", "*.png"),
				new FileChooser.ExtensionFilter("Graphics Interchange Format (*.gif)", "*.gif"));

		File backgroundFile = fileChooser.showOpenDialog(shootOFFStage);

		if (backgroundFile != null) {
			LocatedImage img = new LocatedImage(backgroundFile.toURI().toString());
			arenaController.setBackground(img);
		}
	}

	@FXML
	public void saveCourseMenuItemClicked(ActionEvent event) {
		File coursesDir = new File(System.getProperty("shootoff.courses"));

		if (!coursesDir.exists()) {
			if (!coursesDir.mkdirs()) {
				logger.error("Courses folder does not exist and cannot be created: {}", coursesDir.getAbsolutePath());
			}
		}

		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Save Course");
		fileChooser.setInitialDirectory(coursesDir);
		fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Course File (*.course)", "*.course"));

		File courseFile = fileChooser.showSaveDialog(shootOFFStage);

		if (courseFile != null) {
			String path = courseFile.getPath();
			if (!path.endsWith(".course")) path += ".course";

			courseFile = new File(path);

			CourseIO.saveCourse(arenaController, courseFile);
		}
	}

	@FXML
	public void loadCourseMenuItemClicked(ActionEvent event) {
		File coursesDir = new File(System.getProperty("shootoff.courses"));

		if (!coursesDir.exists()) {
			if (!coursesDir.mkdirs()) {
				logger.error("Courses folder does not exist and cannot be created: {}", coursesDir.getAbsolutePath());
			}
		}

		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Open Course");
		fileChooser.setInitialDirectory(coursesDir);
		fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Course File (*.course)", "*.course"));

		File courseFile = fileChooser.showOpenDialog(shootOFFStage);

		if (courseFile != null) {
			Optional<Course> course = CourseIO.loadCourse(arenaController, courseFile);

			if (course.isPresent()) {
				arenaController.setCourse(course.get());
			}
		}
	}

	@FXML
	public void toggleArenaShotsClicked(ActionEvent event) {
		if (toggleArenaShotsMenuItem.getText().equals("Show Shot Markers")) {
			toggleArenaShotsMenuItem.setText("Hide Shot Markers");
			arenaController.getCanvasManager().setShowShots(true);
		} else {
			toggleArenaShotsMenuItem.setText("Show Shot Markers");
			arenaController.getCanvasManager().setShowShots(false);
		}
	}

	@FXML
	public void resetClicked(ActionEvent event) {
		reset();
	}

	@Override
	public void reset() {
		camerasSupervisor.reset();

		if (config.getExercise().isPresent()) {
			List<Target> knownTargets = new ArrayList<Target>();
			knownTargets.addAll(getTargets());

			if (arenaController != null) {
				knownTargets.addAll(arenaController.getCanvasManager().getTargets());
			}

			config.getExercise().get().reset(knownTargets);
		}

		disableShotDetection(1000);
	}

	// Technically the period could be shorter than the previous call
	// and we don't handle that right now. I'm not too worried about that
	// because I don't think the periods are going to be vastly different
	// This is only intended for very short disablement periods
	public void disableShotDetection(int msDuration) {
		// Don't disable the cameras if they are already disabled (e.g. because
		// a training protocol paused shot detection)
		if (!camerasSupervisor.areDetecting()) return;

		// Keep track of cameras that already had shot detection off so that
		// we can ensure they stay off when we re-enable shot detection
		Set<CameraManager> alreadyOff = new HashSet<>();

		for (CameraManager cm : camerasSupervisor.getCameraManagers()) {
			if (!cm.isDetecting()) alreadyOff.add(cm);
		}

		camerasSupervisor.setDetectingAll(false);

		Runnable restartDetection = () -> {
			if (!calibrationManager.isPresent()
					|| (calibrationManager.isPresent() && !calibrationManager.get().isCalibrating())) {
				if (alreadyOff.isEmpty()) {
					camerasSupervisor.setDetectingAll(true);
				} else {
					for (CameraManager cm : camerasSupervisor.getCameraManagers()) {
						if (!alreadyOff.contains(cm)) cm.setDetecting(true);
					}
				}
			} else {
				logger.info("disableShotDetectionTimer did not re-enable shot detection, isCalibrating is true");
			}
		};

		TimerPool.schedule(restartDetection, msDuration);
	}

	@Override
	public void showCameraLockError(Camera webcam, boolean allCamerasFailed) {
		Platform.runLater(() -> {
			Alert cameraAlert = new Alert(AlertType.ERROR);
			cameraAlert.setTitle("Webcam Locked");
			cameraAlert.setHeaderText("Cannot Open Webcam");
			cameraAlert.setResizable(true);
			cameraAlert.getDialogPane().getScene().getWindow().requestFocus();

			String messageFormat;

			if (allCamerasFailed) {
				messageFormat = "Cannot open the webcam %s. It is being "
						+ "used by another program or it is an IPCam with the wrong credentials. This "
						+ "is the only configured camera, thus ShootOFF must close.";
			} else {
				messageFormat = "Cannot open the webcam %s. It is being "
						+ "used by another program, it is an IPCam with the wrong credentials, or you "
						+ "have ShootOFF open more than once.";
			}

			Optional<String> webcamName = config.getWebcamsUserName(webcam);

			cameraAlert.setContentText(
					String.format(messageFormat, webcamName.isPresent() ? webcamName.get() : webcam.getName()));

			if (allCamerasFailed) {
				cameraAlert.showAndWait();
				Main.forceClose(-1);
			} else {
				cameraAlert.show();
			}
		});
	}

	@Override
	public void showMissingCameraError(Camera webcam) {
		Platform.runLater(() -> {
			Alert cameraAlert = new Alert(AlertType.ERROR);

			Optional<String> cameraName = config.getWebcamsUserName(webcam);
			String messageFormat = "ShootOFF can no longer communicate with the webcam %s. Was it unplugged?";
			String message;
			if (cameraName.isPresent()) {
				message = String.format(messageFormat, cameraName.get());
			} else {
				message = String.format(messageFormat, webcam.getName());
			}

			cameraAlert.setTitle("Webcam Missing");
			cameraAlert.setHeaderText("Cannot Communicate with Camera!");
			cameraAlert.setResizable(true);
			cameraAlert.setContentText(message);
			cameraAlert.initOwner(getStage());
			cameraAlert.show();
		});
	}

	@Override
	public void showFPSWarning(Camera webcam, double fps) {
		Platform.runLater(() -> {
			Alert cameraAlert = new Alert(AlertType.WARNING);

			Optional<String> cameraName = config.getWebcamsUserName(webcam);
			String messageFormat = "The FPS from %s has dropped to %f, which is too low for reliable shot detection. Some"
					+ " shots may be missed. You may be able to raise the FPS by closing other applications.";
			String message;
			if (cameraName.isPresent()) {
				message = String.format(messageFormat, cameraName.get(), fps);
			} else {
				message = String.format(messageFormat, webcam.getName(), fps);
			}

			cameraAlert.setTitle("Webcam FPS Too Low");
			cameraAlert.setHeaderText("Webcam FPS is too low!");
			cameraAlert.setResizable(true);
			cameraAlert.setContentText(message);
			cameraAlert.initOwner(getStage());
			cameraAlert.show();
		});
	}

	@Override
	public void showBrightnessWarning(Camera webcam) {
		Platform.runLater(() -> {
			Alert brightnessAlert = new Alert(AlertType.WARNING);

			Optional<String> cameraName = config.getWebcamsUserName(webcam);
			String messageFormat = "The camera %s is streaming frames that are very bright. "
					+ " This will increase the odds of shots falsely being detected."
					+ " For best results, please do any mix of the following:%n%n"
					+ "-Turn off auto white balance and auto focus on your webcam and reduce the brightness%n"
					+ "-Remove any bright light sources in the camera's view%n"
					+ "-Turn down your projector's brightness and contrast";
			String message;
			if (cameraName.isPresent()) {
				message = String.format(messageFormat, cameraName.get());
			} else {
				message = String.format(messageFormat, webcam.getName());
			}

			brightnessAlert.setTitle("Conditions Very Bright");
			brightnessAlert.setHeaderText("Webcam detected very bright conditions!");
			brightnessAlert.setResizable(true);
			brightnessAlert.setContentText(message);
			brightnessAlert.initOwner(getStage());
			brightnessAlert.show();
		});
	}

	@Override
	public void setExercise(TrainingExercise exercise) {
		try {
			if (exercise == null)
			{
				config.setExercise(null);
				return;
			}
			
			Constructor<?> ctor = exercise.getClass().getConstructor(List.class);

			List<Target> knownTargets = new ArrayList<Target>();
			knownTargets.addAll(getTargets());

			if (arenaController != null) {
				knownTargets.addAll(arenaController.getCanvasManager().getTargets());
			}

			TrainingExercise newExercise = (TrainingExercise) ctor.newInstance(knownTargets);

			Optional<Plugin> plugin = pluginEngine.getPlugin(newExercise);
			if (plugin.isPresent()) {
				config.setPlugin(plugin.get());
			} else {
				config.setPlugin(null);
			}

			config.setExercise(newExercise);

			((TrainingExerciseBase) newExercise).init(config, camerasSupervisor, this);
			newExercise.init();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	@Override
	public void setProjectorExercise(TrainingExercise exercise) {
		try {
			Constructor<?> ctor = exercise.getClass().getConstructor(List.class);
			TrainingExercise newExercise = (TrainingExercise) ctor
					.newInstance(arenaController.getCanvasManager().getTargets());

			Optional<Plugin> plugin = pluginEngine.getPlugin(newExercise);
			if (plugin.isPresent()) {
				config.setPlugin(plugin.get());
			} else {
				config.setPlugin(null);
			}

			config.setExercise(newExercise);

			((ProjectorTrainingExerciseBase) newExercise).init(config, camerasSupervisor, this, arenaController);
			newExercise.init();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	@Override
	public PluginEngine getPluginEngine() {
		return pluginEngine;
	}

	
}
