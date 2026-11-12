package com.radiology;

import com.documents4j.api.DocumentType;
import com.documents4j.api.IConverter;
import com.documents4j.job.LocalConverter;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.animation.Timeline;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.scene.control.DatePicker;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import net.sourceforge.tess4j.Tesseract;
import java.time.LocalDate;

// 7-Zip-JBinding Imports for RAR5 support
import net.sf.sevenzipjbinding.*;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;

import java.awt.Desktop;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Stack;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import com.radiology.settings.WindowState;
import com.radiology.settings.WorkstationSettingsStore;
import com.radiology.settings.DatabaseLocation;
import com.radiology.updates.SourceForgeReleaseService;
import com.radiology.reports.ReportFileService;

public class App extends Application {
    private TextArea textArea = new TextArea();
    private TextField browserSearch = new TextField();
    private TextField historySearch = new TextField();
    private Label statsLabel = new Label("Items: 0 | Reported: 0");
    private ImageView imgViewCurrent = new ImageView();
    private ImageView imgViewPrior = new ImageView();
    private Label statusLabel = new Label("SYSTEM READY");
    private DatePicker datePicker = new DatePicker();
    private LocalDate selectedDate = LocalDate.now();
    
    // Billing & Metadata UI Components
    private ComboBox<String> cbCenter = new ComboBox<>();
    private TextField tfPatientName = new TextField();
    private ComboBox<String> cbScanType = new ComboBox<>();
    private ComboBox<String> cbContrast = new ComboBox<>(FXCollections.observableArrayList("No Contrast", "With Contrast"));
    private ComboBox<String> cbComparison = new ComboBox<>(FXCollections.observableArrayList("No Comparison", "With Comparison"));
    private TextField tfPrice = new TextField();
    private Button btnGenFolder = new Button("📁 NEW OUT");
    private TableView<BillingRecord> billingTable = new TableView<>();
    private ObservableList<BillingRecord> billingData = FXCollections.observableArrayList();
    private Label billingTotalLabel = new Label("Total: 0.00");

    private ObservableList<String> historyData = FXCollections.observableArrayList();
    private ObservableList<String> dicomItems = FXCollections.observableArrayList();
    private ListView<String> historyListView;
    private ListView<String> browserList;
    private Tesseract tesseract = new Tesseract();
    private boolean tesseractReady;
    
    private File outputDirectory;
    private File baseOutputDirectory;
    private File lastTemplateDirectory; 
    private File dicomDirectory; 
    private File lastGeneratedWordFile; 
    private String pacsUrl = "http://localhost:8042/";
    private String pacsUsername = "";
    private String pacsPassword = "";
    private static final String CONFIG_FILE = "workstation.properties";
    private final WorkstationSettingsStore settingsStore = WorkstationSettingsStore.forCurrentUser(CONFIG_FILE);
    private final DatabaseLocation databaseLocation = DatabaseLocation.forCurrentUser("radiology_records.db");
    private final SourceForgeReleaseService releaseService = new SourceForgeReleaseService();
    private String databaseUrl;
    private WindowState windowState = WindowState.defaults();
    private final String LAST_REPORT_JSON = "last_report.json";
    private final java.util.Set<String> reportedFilesCache = new java.util.concurrent.ConcurrentHashMap<>().newKeySet();
    private String selectedTheme = "Classic Dark";

    private Stack<File> backStack = new Stack<>();
    private Stack<File> forwardStack = new Stack<>();
    private boolean isLoadingSettings = false;

    private double currentBasePrice = 0;
    private double currentContrastPrice = 0;
    private double currentComparisonPrice = 0;
    private int editingRecordId = -1;
    private String aiCopiedText = "";
    // ChatGPT authentication is intentionally handled by the user's normal browser.
    // Never collect, persist, or inject OpenAI credentials in this workstation app.
    private static final URI CHATGPT_URI = URI.create("https://chatgpt.com/");
    
    // Performance optimization caches
    private Map<String, Long> browserListCache = new ConcurrentHashMap<>();
    private long browserListCacheTime = 0;
    private static final long BROWSER_CACHE_DURATION = 5000;
    private List<String> historyCache = new java.util.ArrayList<>();
    private long historyCacheTime = 0;
    private static final long HISTORY_CACHE_DURATION = 10000;

    // Executor for background IO and CPU-bound tasks to avoid blocking JavaFX thread
    private ExecutorService ioExecutor;
    private ExecutorService cpuExecutor;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> refreshFuture;
    private HikariDataSource dataSource;
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    private final AtomicReference<Runnable> pendingRefresh = new AtomicReference<>();

    private Connection getConnection() throws SQLException {
        if (dataSource != null) return dataSource.getConnection();
        if (databaseUrl == null) throw new SQLException("The workstation database location is not initialized.");
        return DriverManager.getConnection(databaseUrl);
    }

    @Override
    public void start(Stage stage) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "true");
        
        // Enable WebView caching and optimize memory
        System.setProperty("javafx.webkit.cacheEnabled", "true");
        System.setProperty("javafx.webkit.maxCacheSize", "50");
        System.setProperty("javafx.webkit.threadModel", "single");
        
        // Reduce memory footprint
        System.setProperty("javafx.animation.pulse", "60");
        System.setProperty("prism.lcdtext", "false");
        System.setProperty("prism.text", "t2k");
        
        // initialize executors for background work
        ioExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        cpuExecutor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        scheduler = Executors.newSingleThreadScheduledExecutor();

        try {
            databaseUrl = "jdbc:sqlite:" + databaseLocation.resolve();
        } catch (IOException e) {
            logger.error("Could not prepare the workstation database in {}", databaseLocation.applicationDataDirectory(), e);
            showError("Database Initialization Error",
                    "The workstation database could not be prepared.\n" + errorMessage(e));
            Platform.exit();
            return;
        }

        // configure HikariCP data source for SQLite
        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(databaseUrl);
            cfg.setMaximumPoolSize(10);
            // Use WAL for better concurrency on SQLite
            cfg.addDataSourceProperty("journal_mode", "WAL");
            // Ensure WAL and reasonable synchronous mode for better concurrent performance
            cfg.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");
            dataSource = new HikariDataSource(cfg);
        } catch (Exception e) {
            logger.warn("Could not initialize HikariCP, falling back to DriverManager: {}", e.getMessage());
            dataSource = null;
        }

        // Run DB initialization off the JavaFX thread
        ioExecutor.submit(() -> initDatabase());

        showLogin(stage);
    }

    private void showMainApp(Stage stage) {
        initTesseract();
        
        try {
            SevenZip.initSevenZipFromPlatformJAR();
        } catch (Exception e) {
            logger.warn("7-Zip archive support could not be initialized", e);
            statusLabel.setText("7-ZIP ARCHIVE SUPPORT UNAVAILABLE");
        }

        try {
            InputStream is = getClass().getResourceAsStream("/icon.png");
            if (is != null) stage.getIcons().add(new Image(is));
        } catch (RuntimeException e) {
            logger.warn("Could not load the application window icon", e);
        }

        Label title = new Label("ELITERADS PRO PLATFORM");
        title.getStyleClass().add("header-label");
        Button btnAiAssistant = new Button("AI Assistant");
        btnAiAssistant.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-color: #18a957; -fx-text-fill: white; -fx-border-color: #555; -fx-border-width: 1; -fx-padding: 10 16 10 16;");
        
        // Calendar button with DatePicker
        datePicker.setValue(LocalDate.now());
        datePicker.setPromptText("Select Date");
        datePicker.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-border-width: 1; -fx-padding: 8 12 8 12;");
        datePicker.setPrefWidth(180);
        
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox appHeader = new HBox(10, title, headerSpacer, btnAiAssistant, datePicker);
        appHeader.setAlignment(Pos.CENTER_LEFT);

        // --- BUTTONS ---
        Button btnSnipCurrent = new Button("📷 Name");
        Button btnSnipPrior = new Button("Image");
        Button btnCopyCurr = new Button("COPY1");
        Button btnCopyPrior = new Button("COPY2");
        Button btnCopyText = new Button("📄 COPY Name");
        Button btnGenerate = new Button("🚀 Report");
        Button btnExportPDF = new Button("📋 PDF");
        Button btnSettings = new Button("⚙️ SETTINGS");
        Button btnZip = new Button("📦 ZIP");
        Button btnTheme = new Button("🎨 THEME");
        Button btnClear = new Button("❌ CLEAR");
        Button btnRefresh = new Button("🔄 REFRESH");
        Button btnPacs = new Button("🏥 PACS");
        Button btnBack = new Button("⬅️");
        Button btnForward = new Button("➡️");

        btnExportPDF.getStyleClass().add("pdf-button-3d");

        // Style main buttons to make icons colorful and larger
        Arrays.asList(btnSnipCurrent, btnSnipPrior, btnCopyCurr, btnCopyPrior, btnCopyText, btnGenerate, btnExportPDF, 
                     btnClear, btnRefresh, btnTheme, btnSettings, btnZip, btnBack, btnForward)
              .forEach(b -> b.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 16px; -fx-font-weight: bold;"));

        HBox groupLeft = new HBox(5, btnSnipCurrent, btnSnipPrior, btnCopyCurr, btnCopyPrior);
        HBox groupCenter = new HBox(5, btnCopyText, btnGenerate, btnExportPDF, btnClear, btnRefresh );
        HBox groupRight = new HBox(5, btnTheme, btnSettings, btnZip, btnPacs );

        Region spacer1 = new Region(); Region spacer2 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS); HBox.setHgrow(spacer2, Priority.ALWAYS);

        HBox buttonBar = new HBox(10, groupLeft, spacer1, groupCenter, spacer2, groupRight);
        buttonBar.setAlignment(Pos.CENTER);
        buttonBar.getStyleClass().add("control-deck");

        HBox navBar = new HBox(5, btnBack, btnForward, browserSearch);
        HBox.setHgrow(browserSearch, Priority.ALWAYS);
        
        // --- LIST VIEWS ---
        FilteredList<String> filteredHistory = new FilteredList<>(historyData, p -> true);
        historySearch.textProperty().addListener((obs, old, newVal) -> filteredHistory.setPredicate(name -> newVal == null || newVal.isEmpty() || name.toLowerCase().contains(newVal.toLowerCase())));
        historyListView = new ListView<>(filteredHistory);
        setupHistoryCells();
        setupHistoryContextMenu();

        Label historyTitle = new Label("REPORT HISTORY");
        historyTitle.getStyleClass().add("header-label");
        VBox sidebarLeft = new VBox(10, historyTitle, historySearch, historyListView);
        sidebarLeft.getStyleClass().add("panel");
        sidebarLeft.setPrefWidth(600); 
        VBox.setVgrow(historyListView, Priority.ALWAYS);

        textArea.getStyleClass().add("orange-text-area");
        textArea.setWrapText(true);
        textArea.setPrefHeight(130);

        FilteredList<String> filteredBrowser = new FilteredList<>(dicomItems, p -> true);
        browserSearch.textProperty().addListener((obs, old, newVal) -> filteredBrowser.setPredicate(name -> newVal == null || newVal.isEmpty() || name.toLowerCase().contains(newVal.toLowerCase())));
        browserList = new ListView<>(filteredBrowser);
        setupBrowserCells();
        setupBrowserContextMenu();

        // Setup Billing Section
        VBox billingSection = createBillingSection();
        setupBillingTable();

        Label reportingTitle = new Label("Captured Name");
        reportingTitle.getStyleClass().add("header-label");
        Label browserTitle = new Label("DICOM BROWSER");
        browserTitle.getStyleClass().add("header-label");

        VBox sidebarMiddle = new VBox(10, reportingTitle, textArea, browserTitle, navBar, browserList, statsLabel);
        sidebarMiddle.getStyleClass().add("panel");
        sidebarMiddle.setPrefWidth(600); 
        VBox.setVgrow(browserList, Priority.ALWAYS);

        StackPane panelCurrent = createPanel(imgViewCurrent);
        StackPane panelPrior = createPanel(imgViewPrior);
        VBox imageContainer = new VBox(10, panelCurrent, panelPrior);
        VBox.setVgrow(panelCurrent, Priority.ALWAYS);
        VBox.setVgrow(panelPrior, Priority.ALWAYS);

        VBox sidebarRight = new VBox(10, billingSection, imageContainer);
        sidebarRight.getStyleClass().add("panel");
        sidebarRight.setPrefWidth(270);
        VBox.setVgrow(imageContainer, Priority.ALWAYS);

        SplitPane workspace = new SplitPane(sidebarLeft, sidebarMiddle, sidebarRight);
        workspace.setDividerPositions(0.3, 0.7);
        workspace.setPadding(new Insets(15));
        VBox.setVgrow(workspace, Priority.ALWAYS);

        VBox root = new VBox(10, appHeader, buttonBar, workspace, statusLabel);
        root.getStyleClass().add("root-bg");

        // --- ACTIONS ---
        btnBack.setOnAction(e -> navigateBack());
        btnForward.setOnAction(e -> navigateForward());
        btnRefresh.setOnAction(e -> { refreshBrowserList(); loadHistoryFromFolder(); });
        btnSnipCurrent.setOnAction(e -> handleSnip(stage, imgViewCurrent, true));
        btnSnipPrior.setOnAction(e -> handleSnip(stage, imgViewPrior, false));
        btnCopyCurr.setOnAction(e -> copyImageToClipboard(imgViewCurrent.getImage()));
        btnCopyPrior.setOnAction(e -> copyImageToClipboard(imgViewPrior.getImage()));
        btnCopyText.setOnAction(e -> copyTextWithFeedback());
        btnTheme.setOnAction(e -> chooseTheme(stage, root));
        btnZip.setOnAction(e -> handleZip(browserList.getSelectionModel().getSelectedItem()));
        btnGenerate.setOnAction(e -> handleWordGeneration(stage));
        btnExportPDF.setOnAction(e -> convertWordToPDF());
        btnClear.setOnAction(e -> clearAll());
        btnPacs.setOnAction(e -> openPacsBrowser(stage));
        btnAiAssistant.setOnAction(e -> openAiAssistant(stage));
        btnSettings.setOnAction(e -> showSettingsDialog(stage));
        
        // Calendar date change listener
        datePicker.setOnAction(e -> {
            if (datePicker.getValue() != null) {
                selectedDate = datePicker.getValue();
                updateOutputDirectoryForDate();
                refreshBrowserList();
                loadHistoryFromFolder();
            }
        });

        loadSettings();
        
        // Initialize output directory based on selected date and center
        if (outputDirectory == null && cbCenter.getValue() != null && baseOutputDirectory != null) {
            updateOutputDirectoryForDate();
        }
        
        refreshBrowserList();
        loadHistoryFromFolder();
        startAutoRefresh();

        Scene scene = new Scene(root);
        try {
            var css = getClass().getResource("/style.css");
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
        } catch(Exception e) {
            logger.warn("Could not load application stylesheet", e);
            statusLabel.setText("APPLICATION STYLE COULD NOT BE LOADED");
        }
        applyTheme(root);
        stage.setScene(scene);
        stage.setTitle("Eliterads Pro Platform");
        restoreMainWindow(stage);
        stage.show();
    }

    private void restoreMainWindow(Stage stage) {
        stage.setX(windowState.x());
        stage.setY(windowState.y());
        stage.setWidth(windowState.width());
        stage.setHeight(windowState.height());
        stage.setMaximized(windowState.maximized());

        PauseTransition saveDelay = new PauseTransition(Duration.millis(500));
        saveDelay.setOnFinished(event -> saveWindowState(stage));
        stage.xProperty().addListener((observable, oldValue, newValue) -> scheduleWindowStateSave(stage, saveDelay));
        stage.yProperty().addListener((observable, oldValue, newValue) -> scheduleWindowStateSave(stage, saveDelay));
        stage.widthProperty().addListener((observable, oldValue, newValue) -> scheduleWindowStateSave(stage, saveDelay));
        stage.heightProperty().addListener((observable, oldValue, newValue) -> scheduleWindowStateSave(stage, saveDelay));
        stage.maximizedProperty().addListener((observable, oldValue, newValue) -> saveDelay.playFromStart());
        stage.setOnHiding(event -> saveWindowState(stage));
    }

    private void scheduleWindowStateSave(Stage stage, PauseTransition saveDelay) {
        if (!stage.isMaximized() && !stage.isIconified()) {
            saveDelay.playFromStart();
        }
    }

    private void saveWindowState(Stage stage) {
        windowState = windowState.withMaximized(stage.isMaximized());
        if (!stage.isMaximized() && !stage.isIconified()
                && Double.isFinite(stage.getX()) && Double.isFinite(stage.getY())
                && stage.getWidth() > 0 && stage.getHeight() > 0) {
            windowState = new WindowState(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), false);
        }
        saveSettings();
    }

    private void startAutoRefresh() {
        // Optimized auto-refresh with reduced frequency and caching
        Timeline refreshTimer = new Timeline(new KeyFrame(Duration.seconds(60), e -> {
            // Clear cache before refresh
            browserListCache.clear();
            browserListCacheTime = 0;
            reportedFilesCache.clear();
            
            refreshBrowserList();
            loadHistoryFromFolder();
            loadBillingData();
        }));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();
    }

    private void initDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS billing_records (" +
                         "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "center TEXT, patient TEXT, scan_type TEXT, " +
                         "contrast TEXT, comparison TEXT, " +
                         "base_price REAL DEFAULT 0, " +
                         "contrast_price REAL DEFAULT 0, " +
                         "comparison_price REAL DEFAULT 0, " +
                         "price REAL, " +
                         "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS centers (" +
                         "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "name TEXT UNIQUE, " +
                         "contrast_price REAL DEFAULT 0, " +
                         "comparison_price REAL DEFAULT 0)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS scan_configs (" +
                         "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "center_name TEXT, " +
                         "scan_name TEXT, " +
                         "price REAL, " +
                         "UNIQUE(center_name, scan_name))");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS users (" +
                         "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "username TEXT UNIQUE, " +
                         "password TEXT)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS report_templates (" +
                         "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "section TEXT NOT NULL, " +
                         "modality TEXT NOT NULL, " +
                         "title TEXT NOT NULL, " +
                         "content_html TEXT NOT NULL, " +
                         "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS report_signatures (" +
                         "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "title TEXT NOT NULL, " +
                         "signature_text TEXT NOT NULL, " +
                         "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    stmt.executeUpdate("INSERT INTO users (username, password) VALUES ('admin', 'admin')");
                }
            }

            loadCenters();
            loadBillingData();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private VBox createBillingSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(5);

        ColumnConstraints c0 = new ColumnConstraints(); c0.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setHgrow(Priority.ALWAYS);
        ColumnConstraints c2 = new ColumnConstraints(); c2.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints c3 = new ColumnConstraints(); c3.setHgrow(Priority.SOMETIMES);
        grid.getColumnConstraints().addAll(c0, c1, c2, c3);

        Button btnAddCenter = new Button("+");
        btnAddCenter.setOnAction(e -> handleAddCenter());
        Button btnEditCenter = new Button("✏️");
        btnEditCenter.setOnAction(e -> handleEditCenter());
        btnGenFolder.setOnAction(e -> handleGenerateCenterFolder((Stage) cbCenter.getScene().getWindow()));
        btnGenFolder.setTooltip(new Tooltip("Generate Monthly Folder for Center"));

        FlowPane centerBox = new FlowPane(5, 5, cbCenter, btnAddCenter, btnEditCenter, btnGenFolder);

        Button btnFetchName = new Button("🔍");
        btnFetchName.setOnAction(e -> autoImportPatientName());
        HBox nameBox = new HBox(5, tfPatientName, btnFetchName);
        HBox.setHgrow(tfPatientName, Priority.ALWAYS);

        Button btnAddScan = new Button("+");
        btnAddScan.setOnAction(e -> handleAddScanType());
        Button btnEditScan = new Button("✏️");
        btnEditScan.setOnAction(e -> handleEditScanPrice());
        FlowPane scanBox = new FlowPane(5, 5, cbScanType, btnAddScan, btnEditScan);

        grid.add(new Label("Center:"), 0, 0); grid.add(centerBox, 1, 0);
        grid.add(new Label("Patient:"), 0, 1); grid.add(nameBox, 1, 1);
        grid.add(new Label("Scan:"), 0, 2); grid.add(scanBox, 1, 2);
        grid.add(new Label("Contrast:"), 2, 0); grid.add(cbContrast, 3, 0);
        grid.add(new Label("Compare:"), 2, 1); grid.add(cbComparison, 3, 1);
        grid.add(new Label("Price:"), 2, 2); grid.add(tfPrice, 3, 2);
        tfPrice.setEditable(false);

        cbCenter.setOnAction(e -> {
            String center = cbCenter.getValue();
            loadScanTypes(center);
            loadCenterExtraPrices(center);
            if (center != null && baseOutputDirectory != null) {
                updateOutputDirectoryForDate();
                loadHistoryFromFolder();
                refreshBrowserList();
                statusLabel.setText("OUTPUT SET TO DATE: " + selectedDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
                saveSettings();
            }
            updateTotalPrice();
            updateBillingTotal();
        });
        cbScanType.setOnAction(e -> { 
            updatePriceForScan(cbScanType.getValue());
            updateTotalPrice();
        });
        cbContrast.setOnAction(e -> updateTotalPrice());
        cbComparison.setOnAction(e -> updateTotalPrice());

        cbCenter.valueProperty().addListener((obs, old, val) -> { if(!isLoadingSettings) saveSettings(); });
        cbScanType.valueProperty().addListener((obs, old, val) -> { if(!isLoadingSettings) saveSettings(); });

        Button btnSave = new Button("💾 SAVE RECORD");
        Button btnExport = new Button("📊 EXCEL EXPORT");
        Button btnDelete = new Button("🗑️ DELETE");
        Button btnImport = new Button("📥 IMPORT DB");
        Button btnNewRecord = new Button("🆕 NEW");
        
        btnSave.setOnAction(e -> handleSaveRecord());
        btnExport.setOnAction(e -> handleExportExcel());
        btnDelete.setOnAction(e -> handleDeleteRecord());
        btnImport.setOnAction(e -> handleImportDatabase((Stage) btnImport.getScene().getWindow()));
        btnNewRecord.setOnAction(e -> clearBillingSelection());

        billingTotalLabel.getStyleClass().add("header-label");
        FlowPane actions = new FlowPane(10, 10, btnNewRecord, btnSave, btnDelete, btnExport, btnImport, billingTotalLabel);
        billingTable.setPrefHeight(150);

        Arrays.asList(btnAddCenter, btnEditCenter, btnGenFolder, btnFetchName, btnAddScan, btnEditScan, btnSave, btnExport, btnDelete, btnImport)
              .forEach(b -> b.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 16px; -fx-font-weight: bold;"));

        Label billingTitle = new Label("BILLING & METADATA");
        billingTitle.getStyleClass().add("header-label");

        return new VBox(10, billingTitle, grid, actions, billingTable);
    }

    private void setupBillingTable() {
        TableColumn<BillingRecord, String> colCenter = new TableColumn<>("Center");
        colCenter.setCellValueFactory(new PropertyValueFactory<>("center"));
        TableColumn<BillingRecord, String> colPatient = new TableColumn<>("Patient");
        colPatient.setCellValueFactory(new PropertyValueFactory<>("patient"));
        
        TableColumn<BillingRecord, Double> colBase = new TableColumn<>("Base");
        colBase.setCellValueFactory(new PropertyValueFactory<>("basePrice"));
        TableColumn<BillingRecord, Double> colContrast = new TableColumn<>("Contrast");
        colContrast.setCellValueFactory(new PropertyValueFactory<>("contrastPrice"));
        TableColumn<BillingRecord, Double> colCompare = new TableColumn<>("Compare");
        colCompare.setCellValueFactory(new PropertyValueFactory<>("comparisonPrice"));

        TableColumn<BillingRecord, Double> colPrice = new TableColumn<>("Total");
        colPrice.setCellValueFactory(new PropertyValueFactory<>("price"));
        
        billingTable.getColumns().addAll(colCenter, colPatient, colBase, colContrast, colCompare, colPrice);
        billingTable.setItems(billingData);

        billingTable.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            if (val != null) {
                cbCenter.setValue(val.getCenter());
                tfPatientName.setText(val.getPatient());
                cbScanType.setValue(val.getScanType());
                cbContrast.setValue(val.getContrast());
                cbComparison.setValue(val.getComparison());
                currentBasePrice = val.getBasePrice();
                editingRecordId = val.getId();
                updateTotalPrice();
            }
        });
    }

    private void loadCenters() {
        // Run DB query on background executor and update UI on FX thread
        ioExecutor.submit(() -> {
            java.util.List<String> centers = new java.util.ArrayList<>();
            try (Connection conn = getConnection()) {
                ResultSet rs = conn.createStatement().executeQuery("SELECT name FROM centers");
                while (rs.next()) centers.add(rs.getString("name"));
            } catch (SQLException e) { e.printStackTrace(); }
            Platform.runLater(() -> cbCenter.getItems().setAll(centers));
        });
    }

    private void loadScanTypes(String centerName) {
        cbScanType.getItems().clear();
        if (centerName == null) return;
        ioExecutor.submit(() -> {
            java.util.List<String> scans = new java.util.ArrayList<>();
            try (Connection conn = getConnection()) {
                PreparedStatement pstmt = conn.prepareStatement("SELECT scan_name FROM scan_configs WHERE center_name = ?");
                pstmt.setString(1, centerName);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) scans.add(rs.getString("scan_name"));
            } catch (SQLException e) { e.printStackTrace(); }
            Platform.runLater(() -> cbScanType.getItems().setAll(scans));
        });
    }

    private void updatePriceForScan(String scanName) {
        if (scanName == null || cbCenter.getValue() == null) return;
        final String center = cbCenter.getValue();
        ioExecutor.submit(() -> {
            double price = 0;
            try (Connection conn = getConnection()) {
                PreparedStatement pstmt = conn.prepareStatement("SELECT price FROM scan_configs WHERE center_name = ? AND scan_name = ?");
                pstmt.setString(1, center);
                pstmt.setString(2, scanName);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) price = rs.getDouble("price");
            } catch (SQLException e) { e.printStackTrace(); }
            final double p = price;
            Platform.runLater(() -> {
                currentBasePrice = p;
                updateTotalPrice();
            });
        });
    }

    private void loadCenterExtraPrices(String centerName) {
        currentContrastPrice = 0;
        currentComparisonPrice = 0;
        if (centerName == null) return;
        ioExecutor.submit(() -> {
            double cont = 0, comp = 0;
            try (Connection conn = getConnection()) {
                PreparedStatement pstmt = conn.prepareStatement("SELECT contrast_price, comparison_price FROM centers WHERE name = ?");
                pstmt.setString(1, centerName);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    cont = rs.getDouble("contrast_price");
                    comp = rs.getDouble("comparison_price");
                }
            } catch (SQLException e) { e.printStackTrace(); }
            final double fcont = cont, fcomp = comp;
            Platform.runLater(() -> {
                currentContrastPrice = fcont;
                currentComparisonPrice = fcomp;
                updateTotalPrice();
            });
        });
    }

    private void updateTotalPrice() {
        double total = currentBasePrice;
        if ("With Contrast".equals(cbContrast.getValue())) total += currentContrastPrice;
        if ("With Comparison".equals(cbComparison.getValue())) total += currentComparisonPrice;
        tfPrice.setText(String.format("%.2f", total));
    }

    private void handleAddCenter() {
        Dialog<String[]> dialog = new Dialog<>();
        applyDialogTheme(dialog);
        dialog.setTitle("Add Center");
        ButtonType addButton = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButton, ButtonType.CANCEL);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(20, 10, 10, 10));
        TextField nameField = new TextField();
        TextField contrastPriceField = new TextField("0");
        TextField comparisonPriceField = new TextField("0");
        grid.add(new Label("Center Name:"), 0, 0); grid.add(nameField, 1, 0);
        grid.add(new Label("Contrast Price:"), 0, 1); grid.add(contrastPriceField, 1, 1);
        grid.add(new Label("Comparison Price:"), 0, 2); grid.add(comparisonPriceField, 1, 2);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(b -> b == addButton ? new String[]{nameField.getText(), contrastPriceField.getText(), comparisonPriceField.getText()} : null);
        dialog.showAndWait().ifPresent(res -> {
            String name = res[0] != null ? res[0].trim() : "";
            if (name.isEmpty()) return;
            try (Connection conn = getConnection()) {
                PreparedStatement pstmt = conn.prepareStatement("INSERT OR IGNORE INTO centers (name, contrast_price, comparison_price) VALUES (?, ?, ?)");
                pstmt.setString(1, name);
                pstmt.setDouble(2, Double.parseDouble(res[1].isEmpty() ? "0" : res[1]));
                pstmt.setDouble(3, Double.parseDouble(res[2].isEmpty() ? "0" : res[2]));
                pstmt.executeUpdate();
                loadCenters();
                cbCenter.setValue(res[0]);
            } catch (Exception e) {
                reportError("Center Save Error", "Could not save the center settings.", e);
            }
        });
    }

    private void handleGenerateCenterFolder(Stage stage) {
        String center = cbCenter.getValue();
        if (center == null || center.trim().isEmpty()) { statusLabel.setText("SELECT CENTER FIRST"); return; }
        
        if (baseOutputDirectory == null) {
            baseOutputDirectory = new DirectoryChooser().showDialog(stage);
            if (baseOutputDirectory == null) return;
            saveSettings();
        }
        String monthYear = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-yyyy"));
        File newFolder = new File(baseOutputDirectory, center + "_" + monthYear);
        if (!newFolder.exists() && !newFolder.mkdirs()) {
            reportError("Output Folder Error", "Could not create the center output folder.",
                    new IOException(newFolder.getAbsolutePath()));
            return;
        }
        outputDirectory = newFolder;
        saveSettings(); loadHistoryFromFolder(); refreshBrowserList();
        statusLabel.setText("OUTPUT SET: " + newFolder.getName());
    }

    private void updateOutputDirectoryForDate() {
        String center = cbCenter.getValue();
        if (center == null || baseOutputDirectory == null) return;
        outputDirectory = null;
        
        String monthYear = selectedDate.format(DateTimeFormatter.ofPattern("MM-yyyy"));
        String dayMonthYear = selectedDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        
        File monthFolder = new File(baseOutputDirectory, center + "_" + monthYear);
        if (!monthFolder.exists() && !monthFolder.mkdirs()) {
            reportError("Output Folder Error", "Could not create the center output folder.", new IOException(monthFolder.getAbsolutePath()));
            return;
        }
        
        File dayFolder = new File(monthFolder, dayMonthYear);
        if (!dayFolder.exists() && !dayFolder.mkdirs()) {
            reportError("Output Folder Error", "Could not create the dated output folder.", new IOException(dayFolder.getAbsolutePath()));
            return;
        }
        
        outputDirectory = dayFolder;
        saveSettings();
        statusLabel.setText("DATE: " + dayMonthYear + " | " + outputDirectory.getAbsolutePath());
    }

    private void handleEditCenter() {
        String centerName = cbCenter.getValue();
        if (centerName == null || centerName.isEmpty()) { statusLabel.setText("SELECT CENTER FIRST"); return; }

        Dialog<String[]> dialog = new Dialog<>();
        applyDialogTheme(dialog);
        dialog.setTitle("Edit Prices: " + centerName);
        ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, ButtonType.CANCEL);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(20, 10, 10, 10));
        TextField contrastField = new TextField(String.valueOf(currentContrastPrice));
        TextField comparisonField = new TextField(String.valueOf(currentComparisonPrice));
        grid.add(new Label("Contrast Price:"), 0, 0); grid.add(contrastField, 1, 0);
        grid.add(new Label("Comparison Price:"), 0, 1); grid.add(comparisonField, 1, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(b -> b == saveButton ? new String[]{contrastField.getText(), comparisonField.getText()} : null);
        dialog.showAndWait().ifPresent(res -> {
            try (Connection conn = getConnection()) {
                PreparedStatement pstmt = conn.prepareStatement("UPDATE centers SET contrast_price = ?, comparison_price = ? WHERE name = ?");
                pstmt.setDouble(1, Double.parseDouble(res[0].isEmpty() ? "0" : res[0]));
                pstmt.setDouble(2, Double.parseDouble(res[1].isEmpty() ? "0" : res[1]));
                pstmt.setString(3, centerName);
                pstmt.executeUpdate();
                loadCenterExtraPrices(centerName);
                updateTotalPrice();
                statusLabel.setText("CENTER PRICES UPDATED");
            } catch (Exception e) {
                reportError("Center Price Error", "Could not update the selected center prices.", e);
            }
        });
    }

    private void handleEditScanPrice() {
        String center = cbCenter.getValue();
        String scan = cbScanType.getValue();
        if (center == null || scan == null) { statusLabel.setText("SELECT CENTER & SCAN FIRST"); return; }

        TextInputDialog dialog = new TextInputDialog(String.valueOf(currentBasePrice));
        applyDialogTheme(dialog);
        dialog.setTitle("Edit Scan Price");
        dialog.setHeaderText("Update price for: " + scan + " at " + center);
        dialog.showAndWait().ifPresent(newPrice -> {
            try (Connection conn = getConnection()) {
                PreparedStatement pstmt = conn.prepareStatement("UPDATE scan_configs SET price = ? WHERE center_name = ? AND scan_name = ?");
                pstmt.setDouble(1, Double.parseDouble(newPrice));
                pstmt.setString(2, center);
                pstmt.setString(3, scan);
                pstmt.executeUpdate();
                updatePriceForScan(scan);
                statusLabel.setText("SCAN PRICE UPDATED");
            } catch (Exception e) {
                reportError("Scan Price Error", "Could not update the selected scan price.", e);
            }
        });
    }

    private void handleAddScanType() {
        String center = cbCenter.getValue();
        if (center == null) { statusLabel.setText("SELECT CENTER FIRST"); return; }
        Dialog<String[]> dialog = new Dialog<>();
        applyDialogTheme(dialog);
        dialog.setTitle("Add Scan Type");
        ButtonType addButton = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButton, ButtonType.CANCEL);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(20, 10, 10, 10));
        TextField scanName = new TextField(); TextField price = new TextField();
        grid.add(new Label("Scan:"), 0, 0); grid.add(scanName, 1, 0);
        grid.add(new Label("Price:"), 0, 1); grid.add(price, 1, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(b -> b == addButton ? new String[]{scanName.getText(), price.getText()} : null);
        dialog.showAndWait().ifPresent(res -> {
            if (res[0].isEmpty()) return;
            try (Connection conn = getConnection()) {
                PreparedStatement pstmt = conn.prepareStatement("INSERT OR REPLACE INTO scan_configs (center_name, scan_name, price) VALUES (?, ?, ?)");
                pstmt.setString(1, center); pstmt.setString(2, res[0]);
                pstmt.setDouble(3, Double.parseDouble(res[1].isEmpty() ? "0" : res[1]));
                pstmt.executeUpdate();
                loadScanTypes(center); cbScanType.setValue(res[0]); tfPrice.setText(res[1]);
            } catch (Exception e) {
                reportError("Scan Type Error", "Could not save the scan type.", e);
            }
        });
    }

    private void autoImportPatientName() {
        String capturedName = extractPatientName(textArea.getText());
        if (capturedName.isEmpty()) {
            statusLabel.setText("NO PATIENT NAME FOUND. CAPTURE OR ENTER THE REPORT TEXT FIRST.");
            return;
        }
        tfPatientName.setText(capturedName);
        statusLabel.setText("PATIENT NAME IMPORTED. PLEASE VERIFY THE RESULT.");
    }

    private String extractPatientName(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\\n");
        for (String line : lines) {
            String clean = line.toLowerCase();
            if (clean.contains("patient name:") || clean.contains("name:")) {
                return line.substring(line.indexOf(":") + 1).trim();
            }
        }
        for (String line : lines) {
            if (!line.trim().isEmpty() && !line.trim().startsWith("•")) return line.trim();
        }
        return "";
    }

    private String getReportFileBaseName() {
        String patientName = tfPatientName.getText();
        if (patientName == null || patientName.trim().isEmpty()) {
            patientName = extractPatientName(textArea.getText());
        }

        String safeName = patientName == null ? "" : patientName.trim()
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replaceAll("[. ]+$", "");
        if (safeName.isEmpty()) {
            return "Report_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        }
        if (safeName.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            safeName = "_" + safeName;
        }
        return safeName;
    }

    private void clearBillingSelection() {
        editingRecordId = -1;
        tfPatientName.clear();
        cbScanType.getSelectionModel().clearSelection();
        cbContrast.setValue("No Contrast");
        cbComparison.setValue("No Comparison");
        statusLabel.setText("READY FOR NEW RECORD");
    }

    private void handleSaveRecord() {
        String center = cbCenter.getValue();
        String patient = tfPatientName.getText() != null ? tfPatientName.getText().trim() : "";
        String scan = cbScanType.getValue();

        if (center == null || patient.isEmpty() || scan == null) { statusLabel.setText("MISSING INFO: CANNOT SAVE RECORD"); return; }

        // Capture UI values before executing off the FX thread
        final int recordId = editingRecordId;
        final String contrastVal = cbContrast.getValue();
        final String comparisonVal = cbComparison.getValue();
        final double basePriceVal = currentBasePrice;
        final double contrastPriceVal = "With Contrast".equals(contrastVal) ? currentContrastPrice : 0;
        final double comparisonPriceVal = "With Comparison".equals(comparisonVal) ? currentComparisonPrice : 0;
        final double totalVal = basePriceVal + contrastPriceVal + comparisonPriceVal;

        ioExecutor.submit(() -> {
            try (Connection conn = getConnection()) {
                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                PreparedStatement pstmt;
                if (recordId == -1) {
                    pstmt = conn.prepareStatement("INSERT INTO billing_records(center, patient, scan_type, contrast, comparison, base_price, contrast_price, comparison_price, price, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)");
                    pstmt.setString(10, now);
                } else {
                    pstmt = conn.prepareStatement("UPDATE billing_records SET center=?, patient=?, scan_type=?, contrast=?, comparison=?, base_price=?, contrast_price=?, comparison_price=?, price=? WHERE id=?");
                    pstmt.setInt(10, recordId);
                }

                pstmt.setString(1, center);
                pstmt.setString(2, patient);
                pstmt.setString(3, scan);
                pstmt.setString(4, contrastVal);
                pstmt.setString(5, comparisonVal);
                pstmt.setDouble(6, basePriceVal);
                pstmt.setDouble(7, contrastPriceVal);
                pstmt.setDouble(8, comparisonPriceVal);
                pstmt.setDouble(9, totalVal);

                pstmt.executeUpdate();

                Platform.runLater(() -> {
                    loadBillingData();
                    statusLabel.setText(recordId == -1 ? "RECORD SAVED" : "RECORD UPDATED");
                    if (recordId == -1) clearBillingSelection();
                });
            } catch (Exception e) {
                reportError("Database Error", "Could not save the billing record.", e);
            }
        });
    }

    private void loadBillingData() {
        // Load billing data in background, update UI on FX thread
        ioExecutor.submit(() -> {
            java.util.List<BillingRecord> records = new java.util.ArrayList<>();
            try (Connection conn = getConnection()) {
                ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM billing_records ORDER BY id DESC LIMIT 50");
                while (rs.next()) {
                    records.add(new BillingRecord(rs.getInt("id"), rs.getString("center"), rs.getString("patient"),
                                    rs.getString("scan_type"), rs.getString("contrast"), rs.getString("comparison"),
                                    rs.getDouble("base_price"), rs.getDouble("contrast_price"), rs.getDouble("comparison_price"),
                                    rs.getDouble("price"), rs.getString("created_at")));
                }
            } catch (SQLException e) { e.printStackTrace(); }
            Platform.runLater(() -> {
                billingData.setAll(records);
                updateBillingTotal();
            });
        });
    }

    private void updateBillingTotal() {
        String center = cbCenter.getValue();
        if (center == null) return;
        ioExecutor.submit(() -> {
            double total = 0;
            try (Connection conn = getConnection()) {
                PreparedStatement pstmt = conn.prepareStatement("SELECT SUM(price) FROM billing_records WHERE center = ?");
                pstmt.setString(1, center);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next() && rs.getObject(1) != null) {
                    total = rs.getDouble(1);
                }
            } catch (SQLException e) { e.printStackTrace(); }
            final double ftotal = total;
            Platform.runLater(() -> billingTotalLabel.setText(String.format("Center Total: %.2f", ftotal)));
        });
    }

    private void handleDeleteRecord() {
        BillingRecord selected = billingTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        try (Connection conn = getConnection()) {
            PreparedStatement pstmt = conn.prepareStatement("DELETE FROM billing_records WHERE id = ?");
            pstmt.setInt(1, selected.getId());
            pstmt.executeUpdate();
            loadBillingData();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void handleExportExcel() {
        String center = cbCenter.getValue();
        if (center == null || outputDirectory == null) { statusLabel.setText("SELECT CENTER & OUTPUT FIRST"); return; }
        String monthYear = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        File exportFile = new File(outputDirectory, center + "_" + monthYear + "_Export.csv");

        try (PrintWriter writer = new PrintWriter(exportFile)) {
            writer.println("Date,Patient,Scan Type,Contrast,Comparison,Base Price,Contrast Fee,Compare Fee,Total Price");
            double total = 0;
            
            try (Connection conn = getConnection()) {
                PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM billing_records WHERE center = ? AND created_at LIKE ?");
                pstmt.setString(1, center);
                pstmt.setString(2, monthYear + "%");
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    double p = rs.getDouble("price");
                    total += p;
                    writer.printf("%s,%s,%s,%s,%s,%.2f,%.2f,%.2f,%.2f\n", 
                                  rs.getString("created_at"), rs.getString("patient"), 
                                  rs.getString("scan_type"), rs.getString("contrast"), rs.getString("comparison"), 
                                  rs.getDouble("base_price"), rs.getDouble("contrast_price"), rs.getDouble("comparison_price"), p);
                }
            }
            writer.println(",,,,,,,,");
            writer.println("TOTAL,,,,,,,, " + total);
            statusLabel.setText("EXPORTED TO: " + exportFile.getName());
            Desktop.getDesktop().open(exportFile);
        } catch (Exception e) {
            reportError("Export Error", "Could not export the billing records.", e);
        }
    }

    private void handleImportDatabase(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Source Database to Import");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite Database", "*.db"));
        File sourceFile = fc.showOpenDialog(stage);
        if (sourceFile == null) return;

        ioExecutor.submit(() -> {
            try (Connection targetConn = getConnection();
                 Connection sourceConn = DriverManager.getConnection("jdbc:sqlite:" + sourceFile.getAbsolutePath())) {

                targetConn.setAutoCommit(false);

                // Batch insert centers using a set to avoid duplicates
                java.util.Set<String> existingCenters = new java.util.HashSet<>();
                try (Statement tSt = targetConn.createStatement(); ResultSet tRs = tSt.executeQuery("SELECT name FROM centers")) {
                    while (tRs.next()) existingCenters.add(tRs.getString("name"));
                }

                try (Statement st = sourceConn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM centers")) {
                    PreparedStatement ps = targetConn.prepareStatement("INSERT OR IGNORE INTO centers (name, contrast_price, comparison_price) VALUES (?, ?, ?)");
                    int batch = 0;
                    while (rs.next()) {
                        String name = rs.getString("name");
                        if (existingCenters.contains(name)) continue;
                        ps.setString(1, name);
                        ps.setDouble(2, rs.getDouble("contrast_price"));
                        ps.setDouble(3, rs.getDouble("comparison_price"));
                        ps.addBatch();
                        existingCenters.add(name);
                        if (++batch % 500 == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }

                // Batch insert scan_configs
                java.util.Set<String> existingScans = new java.util.HashSet<>();
                try (Statement tSt = targetConn.createStatement(); ResultSet tRs = tSt.executeQuery("SELECT center_name || '|' || scan_name AS key FROM scan_configs")) {
                    while (tRs.next()) existingScans.add(tRs.getString("key"));
                }
                try (Statement st = sourceConn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM scan_configs")) {
                    PreparedStatement ps = targetConn.prepareStatement("INSERT OR IGNORE INTO scan_configs (center_name, scan_name, price) VALUES (?, ?, ?)");
                    int batch = 0;
                    while (rs.next()) {
                        String key = rs.getString("center_name") + "|" + rs.getString("scan_name");
                        if (existingScans.contains(key)) continue;
                        ps.setString(1, rs.getString("center_name"));
                        ps.setString(2, rs.getString("scan_name"));
                        ps.setDouble(3, rs.getDouble("price"));
                        ps.addBatch();
                        existingScans.add(key);
                        if (++batch % 500 == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }

                // Batch insert billing_records: first collect existing keys to avoid per-row checks
                java.util.Set<String> existingBilling = new java.util.HashSet<>();
                try (Statement tSt = targetConn.createStatement(); ResultSet tRs = tSt.executeQuery("SELECT center || '|' || patient || '|' || created_at AS key FROM billing_records")) {
                    while (tRs.next()) existingBilling.add(tRs.getString("key"));
                }

                try (Statement st = sourceConn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM billing_records")) {
                    PreparedStatement insertPs = targetConn.prepareStatement("INSERT INTO billing_records (center, patient, scan_type, contrast, comparison, base_price, contrast_price, comparison_price, price, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    int batch = 0;
                    while (rs.next()) {
                        String key = rs.getString("center") + "|" + rs.getString("patient") + "|" + rs.getString("created_at");
                        if (existingBilling.contains(key)) continue;
                        insertPs.setString(1, rs.getString("center"));
                        insertPs.setString(2, rs.getString("patient"));
                        insertPs.setString(3, rs.getString("scan_type"));
                        insertPs.setString(4, rs.getString("contrast"));
                        insertPs.setString(5, rs.getString("comparison"));
                        insertPs.setDouble(6, rs.getDouble("base_price"));
                        insertPs.setDouble(7, rs.getDouble("contrast_price"));
                        insertPs.setDouble(8, rs.getDouble("comparison_price"));
                        insertPs.setDouble(9, rs.getDouble("price"));
                        insertPs.setString(10, rs.getString("created_at"));
                        insertPs.addBatch();
                        existingBilling.add(key);
                        if (++batch % 500 == 0) insertPs.executeBatch();
                    }
                    insertPs.executeBatch();
                }

                targetConn.commit();
                Platform.runLater(() -> {
                    loadCenters(); loadBillingData();
                    statusLabel.setText("DATA IMPORTED SUCCESSFULLY");
                });
            } catch (Exception e) {
                logger.error("Could not import the selected workstation database", e);
                Platform.runLater(() -> {
                    statusLabel.setText("IMPORT FAILED");
                    showError("Import Error", "Could not import the selected database.\n" + errorMessage(e));
                });
            }
        });
    }

    private static class ReportShortcut {
        private final String section;
        private final String modality;
        private final String title;
        private final String phrase;

        private ReportShortcut(String section, String modality, String title, String phrase) {
            this.section = section;
            this.modality = modality;
            this.title = title;
            this.phrase = phrase;
        }

        private String getSection() { return section; }
        private String getModality() { return modality; }
        private String getTitle() { return title; }
        private String getPhrase() { return phrase; }
    }

    private static class ReportTemplate {
        private final int id;
        private final String section;
        private final String modality;
        private final String title;
        private final String contentHtml;

        private ReportTemplate(int id, String section, String modality, String title, String contentHtml) {
            this.id = id;
            this.section = section;
            this.modality = modality;
            this.title = title;
            this.contentHtml = contentHtml;
        }

        private int getId() { return id; }
        private String getSection() { return section; }
        private String getModality() { return modality; }
        private String getTitle() { return title; }
        private String getContentHtml() { return contentHtml; }
    }

    private static class TemplateImportSummary {
        private final int imported;
        private final int skipped;

        private TemplateImportSummary(int imported, int skipped) {
            this.imported = imported;
            this.skipped = skipped;
        }
    }

    private static class ReportSignature {
        private final int id;
        private final String title;
        private final String text;

        private ReportSignature(int id, String title, String text) {
            this.id = id;
            this.title = title;
            this.text = text;
        }

        private int getId() { return id; }
        private String getTitle() { return title; }
        private String getText() { return text; }
    }

    public static class BillingRecord {
        private int id;
        private String center, patient, scanType, contrast, comparison, date;
        private double basePrice, contrastPrice, comparisonPrice, price;

        public BillingRecord(int id, String center, String patient, String scanType, String contrast, String comparison, 
                             double basePrice, double contrastPrice, double comparisonPrice, double price, String date) {
            this.id = id; this.center = center; this.patient = patient; this.scanType = scanType;
            this.contrast = contrast; this.comparison = comparison; 
            this.basePrice = basePrice; this.contrastPrice = contrastPrice; this.comparisonPrice = comparisonPrice;
            this.price = price; this.date = date;
        }
        public int getId() { return id; }
        public String getCenter() { return center; }
        public String getPatient() { return patient; }
        public String getScanType() { return scanType; }
        public String getContrast() { return contrast; }
        public String getComparison() { return comparison; }
        public double getBasePrice() { return basePrice; }
        public double getContrastPrice() { return contrastPrice; }
        public double getComparisonPrice() { return comparisonPrice; }
        public double getPrice() { return price; }
        public String getDate() { return date; }
    }

    private void setupBrowserContextMenu() {
        ContextMenu cm = new ContextMenu();
        MenuItem miRadiAnt = new MenuItem("☢️ Open in RadiAnt"); 
        miRadiAnt.setStyle("-fx-text-fill: #2196F3;");
        MenuItem miCopy = new MenuItem("📋 Copy File/Folder"); 
        miCopy.setStyle("-fx-text-fill: #2196F3;");
        MenuItem miUnzip = new MenuItem("🔓 Unzip Here"); 
        miUnzip.setStyle("-fx-text-fill: #2196F3;");
        MenuItem miRarToZip = new MenuItem("🔄 Convert RAR to ZIP"); 
        miRarToZip.setStyle("-fx-text-fill: #2196F3;");
        MenuItem miDelete = new MenuItem("🗑️ Delete");
        miDelete.setStyle("-fx-text-fill: #2196F3;");

        miRadiAnt.setOnAction(e -> {
            String item = browserList.getSelectionModel().getSelectedItem();
            if (item != null) openInRadiAnt(new File(dicomDirectory, item.replace("✅ ", "")));
        });

        miCopy.setOnAction(e -> {
            String item = browserList.getSelectionModel().getSelectedItem();
            if (item != null) {
                File fileToCopy = new File(dicomDirectory, item.replace("✅ ", ""));
                if (fileToCopy.exists()) {
                    ClipboardContent content = new ClipboardContent();
                    content.putFiles(Arrays.asList(fileToCopy));
                    Clipboard.getSystemClipboard().setContent(content);
                    statusLabel.setText("ITEM COPIED TO CLIPBOARD");
                }
            }
        });

        miUnzip.setOnAction(e -> handleUnzip(browserList.getSelectionModel().getSelectedItem()));
        miRarToZip.setOnAction(e -> handleRarToZip(browserList.getSelectionModel().getSelectedItem()));

        miDelete.setOnAction(e -> {
            String item = browserList.getSelectionModel().getSelectedItem();
            if (item != null) {
                File f = new File(dicomDirectory, item.replace("✅ ", ""));
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete " + f.getName() + "?", ButtonType.YES, ButtonType.NO);
                applyDialogTheme(confirm);
                confirm.showAndWait().ifPresent(res -> { if (res == ButtonType.YES) { deleteFileRecursive(f); refreshBrowserList(); } });
            }
        });

        cm.getItems().addAll(miRadiAnt, new SeparatorMenuItem(), miCopy, miUnzip, miRarToZip, new SeparatorMenuItem(), miDelete);
        browserList.setContextMenu(cm);
    }

    private void setupHistoryContextMenu() {
        ContextMenu cm = new ContextMenu();
        MenuItem miCopy = new MenuItem("📋 Copy Report File"); 
        miCopy.setStyle("-fx-text-fill: #2196F3;");
        MenuItem miDelete = new MenuItem("🗑️ Delete Report");
        miDelete.setStyle("-fx-text-fill: #2196F3;");
        MenuItem miOpenInAI = new MenuItem("🤖 Open in AI Assistant");
        miOpenInAI.setStyle("-fx-text-fill: #2196F3;");

        miCopy.setOnAction(e -> {
            String item = historyListView.getSelectionModel().getSelectedItem();
            if (item != null && outputDirectory != null) {
                File reportFile = new File(outputDirectory, item);
                if (reportFile.exists()) {
                    ClipboardContent content = new ClipboardContent();
                    content.putFiles(Arrays.asList(reportFile));
                    Clipboard.getSystemClipboard().setContent(content);
                    statusLabel.setText("REPORT COPIED TO CLIPBOARD");
                }
            }
        });

        miDelete.setOnAction(e -> {
            String item = historyListView.getSelectionModel().getSelectedItem();
            if (item != null && outputDirectory != null) {
                File f = new File(outputDirectory, item);
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete " + f.getName() + "?", ButtonType.YES, ButtonType.NO);
                applyDialogTheme(confirm);
                confirm.showAndWait().ifPresent(res -> { if (res == ButtonType.YES) { f.delete(); loadHistoryFromFolder(); refreshBrowserList(); } });
            }
        });

        miOpenInAI.setOnAction(e -> {
            String item = historyListView.getSelectionModel().getSelectedItem();
            if (item != null && outputDirectory != null) {
                File reportFile = new File(outputDirectory, item);
                if (reportFile.exists()) {
                    openAiAssistantWithFile((Stage) historyListView.getScene().getWindow(), reportFile);
                }
            }
        });

        cm.getItems().addAll(miCopy, new SeparatorMenuItem(), miDelete, new SeparatorMenuItem(), miOpenInAI);
        historyListView.setContextMenu(cm);
    }

    private void openInRadiAnt(File folder) {
        if (folder == null) return;
        statusLabel.setText("LAUNCHING RADIANT...");
        new Thread(() -> {
            try {
                String[] paths = {
                    "C:\\Program Files\\RadiAntViewer64bit\\RadiAntViewer.exe",
                    "C:\\Program Files (x86)\\RadiAntViewer\\RadiAntViewer.exe"
                };
                boolean launched = false;
                for (String p : paths) {
                    File exe = new File(p);
                    if (exe.exists()) {
                        new ProcessBuilder(p, folder.getAbsolutePath()).start();
                        launched = true; break;
                    }
                }
                if (!launched) Desktop.getDesktop().open(folder);
                Platform.runLater(() -> statusLabel.setText("RADIANT ACTIVE"));
            } catch (Exception e) {
                reportError("DICOM Viewer Error", "Could not open the selected study in a viewer.", e);
            }
        }, "dicom-viewer-launch").start();
    }

    private void handleUnzip(String itemName) {
        if (itemName == null) return;
        File zipFile = new File(dicomDirectory, itemName.replace("✅ ", ""));
        if (!zipFile.exists() || !zipFile.getName().toLowerCase().endsWith(".zip")) return;
        
        statusLabel.setText("UNZIPPING...");
        new Thread(() -> {
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                Path targetDir = dicomDirectory.toPath().normalize();
                while ((entry = zis.getNextEntry()) != null) {
                    Path targetFile = targetDir.resolve(entry.getName()).normalize();
                    if (!targetFile.startsWith(targetDir)) continue;

                    File newFile = targetFile.toFile();
                    if (entry.isDirectory()) Files.createDirectories(targetFile);
                    else { 
                        Files.createDirectories(targetFile.getParent());
                        try (FileOutputStream fos = new FileOutputStream(newFile)) {
                            byte[] buffer = new byte[4096];
                            int len;
                            while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                        } 
                    }
                    zis.closeEntry();
                }
                Platform.runLater(() -> { statusLabel.setText("UNZIP SUCCESS"); refreshBrowserList(); });
            } catch (Exception e) {
                reportError("Archive Extraction Error", "Could not extract the selected ZIP archive.", e);
            }
        }, "dicom-archive-extraction").start();
    }

    private void handleRarToZip(String itemName) {
        if (itemName == null) return;
        File rarFile = new File(dicomDirectory, itemName.replace("✅ ", ""));
        String baseName = rarFile.getName().substring(0, rarFile.getName().lastIndexOf('.'));
        File zipOut = new File(dicomDirectory, baseName + ".zip");
        statusLabel.setText("EXTRACTING RAR5...");
        new Thread(() -> {
            try (RandomAccessFile raf = new RandomAccessFile(rarFile, "r");
                 IInArchive archive = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf))) {
                try (FileOutputStream fos = new FileOutputStream(zipOut); ZipOutputStream zos = new ZipOutputStream(fos)) {
                    for (int i = 0; i < archive.getNumberOfItems(); i++) {
                        String fileName = (String) archive.getProperty(i, PropID.PATH);
                        fileName = fileName.replace("\\", "/");
                        if ((Boolean) archive.getProperty(i, PropID.IS_FOLDER)) continue;
                        zos.putNextEntry(new ZipEntry(fileName));
                        archive.extract(new int[]{i}, false, new IArchiveExtractCallback() {
                            public ISequentialOutStream getStream(int index, ExtractAskMode askMode) { return data -> { try { zos.write(data); return data.length; } catch (IOException e) { return 0; } }; }
                            public void prepareOperation(ExtractAskMode askMode) {}
                            public void setOperationResult(ExtractOperationResult result) {}
                            public void setCompleted(long complete) {}
                            public void setTotal(long total) {}
                        });
                        zos.closeEntry();
                    }
                }
                Platform.runLater(() -> { statusLabel.setText("CONVERSION SUCCESS"); refreshBrowserList(); });
            } catch (Exception e) {
                reportError("RAR5 Conversion Error", "Could not convert the selected RAR archive.", e);
            }
        }, "dicom-rar-conversion").start();
    }

    // --- SUPPORT METHODS ---
    private StackPane createPanel(ImageView iv) { iv.setPreserveRatio(true); StackPane sp = new StackPane(iv); sp.getStyleClass().add("panel"); sp.setMaxWidth(Double.MAX_VALUE); sp.setMaxHeight(Double.MAX_VALUE); iv.setManaged(false); iv.fitWidthProperty().bind(sp.widthProperty().subtract(20)); iv.fitHeightProperty().bind(sp.heightProperty().subtract(20)); return sp; }
    private void navigateTo(File newDir) { if (newDir != null && newDir.isDirectory()) { if (dicomDirectory != null) backStack.push(dicomDirectory); forwardStack.clear(); dicomDirectory = newDir; refreshBrowserList(); } }
    private void navigateBack() { if (!backStack.isEmpty()) { forwardStack.push(dicomDirectory); dicomDirectory = backStack.pop(); refreshBrowserList(); } }
    private void navigateForward() { if (!forwardStack.isEmpty()) { backStack.push(dicomDirectory); dicomDirectory = forwardStack.pop(); refreshBrowserList(); } }
    private void setupHistoryCells() { historyListView.setCellFactory(lv -> new ListCell<String>() { @Override protected void updateItem(String item, boolean empty) { super.updateItem(item, empty); if (empty || item == null) setText(null); else setText((getIndex() + 1) + ". " + item); } }); historyListView.setOnMouseClicked(e -> { if (e.getClickCount() == 2) openHistoryFile(historyListView.getSelectionModel().getSelectedItem()); }); }
    private void setupBrowserCells() {
        browserList.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    getStyleClass().remove("reported-cell");
                    return;
                }
                boolean done = isReported(item);
                setText(done ? "✅ " + item : item);
                if (done) {
                    if (!getStyleClass().contains("reported-cell")) getStyleClass().add("reported-cell");
                } else {
                    getStyleClass().remove("reported-cell");
                }
            }
        });
        browserList.setOnMouseClicked(event -> {
            if (event.getClickCount() != 2 || dicomDirectory == null) return;
            String selected = browserList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            File selectedFile = new File(dicomDirectory, selected.replace("✅ ", ""));
            if (selectedFile.isDirectory()) {
                navigateTo(selectedFile);
                return;
            }
            try {
                Desktop.getDesktop().open(selectedFile);
            } catch (IOException | UnsupportedOperationException e) {
                reportError("Open DICOM Error", "Could not open the selected DICOM item.", e);
            }
        });
    }
    
    // Cache for browser list to improve performance
    private void refreshBrowserList() { 
        if (dicomDirectory != null && dicomDirectory.exists()) { 
            long currentTime = System.currentTimeMillis();
            if (currentTime - browserListCacheTime < BROWSER_CACHE_DURATION && !browserListCache.isEmpty()) {
                Platform.runLater(() -> {
                    dicomItems.setAll(new java.util.ArrayList<>(browserListCache.keySet()));
                    updateStats();
                });
                return;
            }
            
            new Thread(() -> { 
                File[] allFiles = dicomDirectory.listFiles(); 
                List<String> names = new java.util.ArrayList<>(); 
                if (allFiles != null) { 
                    java.util.Arrays.sort(allFiles, (f1, f2) -> { 
                        if (f1.isDirectory() && !f2.isDirectory()) return -1; 
                        if (!f1.isDirectory() && f2.isDirectory()) return 1; 
                        return Long.compare(f2.lastModified(), f1.lastModified()); 
                    }); 
                    for (File f : allFiles) names.add(f.getName()); 
                } 
                
                browserListCache.clear();
                for (String name : names) {
                    browserListCache.put(name, System.currentTimeMillis());
                }
                browserListCacheTime = currentTime;
                
                Platform.runLater(() -> { 
                    dicomItems.setAll(names); 
                    updateStats(); 
                }); 
            }).start(); 
        } 
    }
    
    private void applyDialogTheme(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("app-dialog");
        if ("Light".equals(selectedTheme)) pane.getStyleClass().add("dialog-light");
        else if ("Visual Studio Dark".equals(selectedTheme)) pane.getStyleClass().add("dialog-visual-studio");
        else pane.getStyleClass().add("dialog-classic-dark");
        java.net.URL themeStylesheet = getClass().getResource("/dialog-theme.css");
        if (themeStylesheet != null) pane.getStylesheets().add(themeStylesheet.toExternalForm());
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        applyDialogTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void reportError(String title, String context, Throwable error) {
        logger.error(context, error);
        String details = errorMessage(error);
        Runnable notify = () -> {
            statusLabel.setText(title.toUpperCase(java.util.Locale.ROOT) + ": " + details);
            showError(title, context + "\n" + details);
        };
        if (Platform.isFxApplicationThread()) {
            notify.run();
        } else {
            Platform.runLater(notify);
        }
    }

    private void showInfo(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        applyDialogTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
    
    // Optimized stats update with caching
    private void updateStats() {
        if (reportedFilesCache.isEmpty() && outputDirectory != null && outputDirectory.exists()) {
            new Thread(() -> {
                reportedFilesCache.clear();
                if (outputDirectory != null && outputDirectory.exists()) {
                    String[] files = outputDirectory.list();
                    if (files != null) for (String f : files) reportedFilesCache.add(f.toLowerCase());
                }
                int reportedCount = 0;
                for (String name : dicomItems) if (isReported(name)) reportedCount++;
                final int finalReported = reportedCount;
                Platform.runLater(() -> statsLabel.setText(String.format("📂 Items: %d | ✅ Reported: %d", dicomItems.size(), finalReported)));
            }).start();
        } else {
            int reportedCount = 0;
            for (String name : dicomItems) if (isReported(name)) reportedCount++;
            statsLabel.setText(String.format("📂 Items: %d | ✅ Reported: %d", dicomItems.size(), reportedCount));
        }
    }
    private boolean isReported(String itemName) {
        if (reportedFilesCache.isEmpty()) return false;
        String clean = itemName.contains(".") ? itemName.substring(0, itemName.lastIndexOf('.')).toLowerCase() : itemName.toLowerCase();
        for (String cachedFile : reportedFilesCache) {
            if (cachedFile.contains(clean)) return true;
        }
        return false;
    }
    
    // Cache for history list
    private void loadHistoryFromFolder() { 
        if (outputDirectory != null && outputDirectory.exists()) { 
            long currentTime = System.currentTimeMillis();
            if (currentTime - historyCacheTime < HISTORY_CACHE_DURATION && !historyCache.isEmpty()) {
                Platform.runLater(() -> historyData.setAll(new java.util.ArrayList<>(historyCache)));
                return;
            }
            
            new Thread(() -> {
                File[] files = outputDirectory.listFiles((dir, name) -> {
                    String lowerName = name.toLowerCase();
                    return lowerName.endsWith(".docx") || lowerName.endsWith(".doc") || 
                           lowerName.endsWith(".pdf") || lowerName.endsWith(".xlsx");
                }); 
                if (files != null) { 
                    List<String> fileNames = new java.util.ArrayList<>();
                    java.util.Arrays.stream(files)
                        .sorted((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()))
                        .forEach(f -> fileNames.add(f.getName())); 
                    
                    historyCache.clear();
                    historyCache.addAll(fileNames);
                    historyCacheTime = currentTime;
                    
                    Platform.runLater(() -> historyData.setAll(fileNames));
                } 
            }).start();
        } 
    }
    private void openHistoryFile(String selectedItem) {
        if (selectedItem == null || outputDirectory == null) return;
        try {
            Desktop.getDesktop().open(new File(outputDirectory, selectedItem));
        } catch (IOException | UnsupportedOperationException e) {
            reportError("Open Report Error", "Could not open the selected report.", e);
        }
    }
    private void handleWordGeneration(Stage s) { 
        if (outputDirectory == null) {
            if (baseOutputDirectory != null && cbCenter.getValue() != null) {
                updateOutputDirectoryForDate();
            }
            if (outputDirectory == null) {
                if (baseOutputDirectory == null) {
                    File selectedBase = new DirectoryChooser().showDialog(s);
                    if (selectedBase == null) {
                        statusLabel.setText("REPORT FOLDER NOT CONFIGURED");
                        return;
                    }
                    baseOutputDirectory = selectedBase;
                    saveSettings();
                }
                if (cbCenter.getValue() != null) {
                    updateOutputDirectoryForDate();
                }
            }
        }

        if (outputDirectory == null) {
            statusLabel.setText("SELECT CENTER AND DATE OR CHOOSE REPORT FOLDER");
            return;
        }

        try {
            Files.createDirectories(outputDirectory.toPath());
        } catch (IOException e) {
            reportError("Output Folder Error", "Could not access the selected report output folder.", e);
            return;
        }

        FileChooser fc = new FileChooser(); 
        if (lastTemplateDirectory != null && lastTemplateDirectory.exists()) fc.setInitialDirectory(lastTemplateDirectory); 
        else if (baseOutputDirectory != null && baseOutputDirectory.exists()) fc.setInitialDirectory(baseOutputDirectory);
        File selected = fc.showOpenDialog(s); 
        if (selected != null) { 
            lastTemplateDirectory = selected.getParentFile(); 
            saveSettings(); 
            try { 
                String rawName = textArea.getText() == null ? "" : textArea.getText();
                String firstLine = rawName.lines().findFirst().orElse("");
                String safeName = sanitizeReportName(firstLine.replace("• ", "").trim());
                String displayName = safeName.isEmpty() ? "Report" : safeName;
                lastGeneratedWordFile = ReportFileService.copyTemplate(
                        selected.toPath(), outputDirectory.toPath(), displayName, LocalDateTime.now()).toFile();
                Desktop.getDesktop().open(lastGeneratedWordFile); 
                loadHistoryFromFolder(); 
                refreshBrowserList(); 
            } catch (Exception ex) { 
                reportError("Report Creation Error", "Could not create or open the report.", ex);
            } 
        } 
    }

    private String sanitizeReportName(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").replaceAll("[. ]+$", "");
    }
    
    private void convertWordToPDF() {
        if (lastGeneratedWordFile == null) return;
        File wordFile = lastGeneratedWordFile;
        new Thread(() -> {
            IConverter converter = null;
            try {
                String path = wordFile.getAbsolutePath();
                int extensionStart = path.lastIndexOf('.');
                File pdfFile = new File((extensionStart < 0 ? path : path.substring(0, extensionStart)) + ".pdf");
                converter = LocalConverter.builder().build();
                try (InputStream input = new FileInputStream(wordFile);
                     OutputStream output = new FileOutputStream(pdfFile)) {
                    converter.convert(input).as(DocumentType.DOCX).to(output).as(DocumentType.PDF).execute();
                }
                Platform.runLater(() -> {
                    loadHistoryFromFolder();
                    refreshBrowserList();
                    try {
                        Desktop.getDesktop().open(pdfFile);
                    } catch (IOException | UnsupportedOperationException e) {
                        reportError("Open PDF Error", "The PDF was created, but could not be opened.", e);
                    }
                });
            } catch (Exception e) {
                reportError("PDF Conversion Error", "Could not convert the report to PDF.", e);
            } finally {
                if (converter != null) {
                    converter.shutDown();
                }
            }
        }, "report-pdf-conversion").start();
    }
    private void handleSnip(Stage s, ImageView iv, boolean ocr) {
        boolean restoreWindow = !s.isIconified();
        try {
            if (restoreWindow) s.setIconified(true);
            PauseTransition minimizeDelay = new PauseTransition(Duration.millis(450));
            minimizeDelay.setOnFinished(event -> {
                try {
                    new SnipOverlay((image, error) -> Platform.runLater(() -> {
                        if (restoreWindow) {
                            try {
                                s.setIconified(false);
                                s.toFront();
                            } catch (RuntimeException ex) {
                                logger.error("Could not restore the window after screen capture", ex);
                                statusLabel.setText("COULD NOT RESTORE WINDOW AFTER SCREEN CAPTURE: " + ex.getMessage());
                                showError("Screen Capture Error",
                                        "Could not restore the main window after capture.\n" + errorMessage(ex));
                                return;
                            }
                        }
                        if (error != null) {
                            String detail = errorMessage(error);
                            statusLabel.setText("SCREEN CAPTURE FAILED: " + detail);
                            showError("Screen Capture Error", "The screen region could not be captured.\n" + detail);
                            return;
                        }
                        if (image == null) {
                            statusLabel.setText("SCREEN CAPTURE CANCELED.");
                            return;
                        }

                        iv.setImage(image);
                        statusLabel.setText("SCREEN IMAGE CAPTURED.");
                        if (ocr) {
                            if (!tesseractReady) {
                                statusLabel.setText("IMAGE CAPTURED, BUT OCR IS NOT READY. CHECK THE APPLICATION LOG.");
                                showError("OCR Unavailable",
                                        "The screen image was captured, but OCR could not be initialized. Restart the app after checking its log.");
                                return;
                            }

                            java.awt.image.BufferedImage capturedImage;
                            try {
                                capturedImage = SwingFXUtils.fromFXImage(image, null);
                            } catch (RuntimeException ex) {
                                logger.error("Could not prepare the captured image for OCR", ex);
                                statusLabel.setText("OCR FAILED: COULD NOT PREPARE THE CAPTURED IMAGE.");
                                showError("OCR Error",
                                        "Could not prepare the captured image for text recognition.\n" + errorMessage(ex));
                                return;
                            }

                            new Thread(() -> {
                                try {
                                    String result = tesseract.doOCR(capturedImage);
                                    Platform.runLater(() -> {
                                        textArea.setText(result);
                                        String capturedName = extractPatientName(result);
                                        if (capturedName.isEmpty()) {
                                            statusLabel.setText("IMAGE CAPTURED, BUT NO PATIENT NAME WAS RECOGNIZED.");
                                            showError("Name Not Recognized",
                                                    "The image was captured, but OCR could not find a patient name. The recognized text remains in the report text area.");
                                        } else {
                                            tfPatientName.setText(capturedName);
                                            statusLabel.setText("PATIENT NAME CAPTURED. PLEASE VERIFY THE OCR RESULT.");
                                        }
                                    });
                                } catch (Exception ex) {
                                    logger.error("Could not read text from the captured image", ex);
                                    Platform.runLater(() -> {
                                        statusLabel.setText("OCR FAILED: " + errorMessage(ex));
                                        showError("OCR Error",
                                                "Could not recognize text in the captured image.\n" + errorMessage(ex));
                                    });
                                }
                            }, "captured-image-ocr").start();
                        }
                    })).show();
                } catch (RuntimeException ex) {
                    logger.error("Could not start screen capture", ex);
                    restoreCaptureWindow(s, restoreWindow, ex);
                }
            });
            minimizeDelay.play();
        } catch (RuntimeException ex) {
            logger.error("Could not start screen capture", ex);
            restoreCaptureWindow(s, restoreWindow, ex);
        }
    }

    private void restoreCaptureWindow(Stage stage, boolean restoreWindow, RuntimeException error) {
        if (restoreWindow) {
            try {
                stage.setIconified(false);
                stage.toFront();
            } catch (RuntimeException restoreError) {
                error.addSuppressed(restoreError);
            }
        }
        statusLabel.setText("SCREEN CAPTURE FAILED: " + errorMessage(error));
        showError("Screen Capture Error", "Could not start screen capture.\n" + errorMessage(error));
    }

    private String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String imageToDataUrl(Image image) throws IOException {
        java.awt.image.BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!javax.imageio.ImageIO.write(bufferedImage, "png", output)) {
                throw new IOException("Could not encode the captured image as PNG.");
            }
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }

    public static final class EditorImageBridge {
        private final App app;
        private final Stage ownerStage;
        private final Stage editorStage;
        private final javafx.scene.web.WebEngine editorEngine;

        public EditorImageBridge(App app, Stage ownerStage, Stage editorStage, javafx.scene.web.WebEngine editorEngine) {
            this.app = app;
            this.ownerStage = ownerStage;
            this.editorStage = editorStage;
            this.editorEngine = editorEngine;
        }

        public void captureKeyImage() {
            boolean restoreOwner = !ownerStage.isIconified();
            boolean restoreEditor = !editorStage.isIconified();
            try {
                if (restoreOwner) ownerStage.setIconified(true);
                if (restoreEditor) editorStage.setIconified(true);
                PauseTransition minimizeDelay = new PauseTransition(Duration.millis(450));
                minimizeDelay.setOnFinished(event -> {
                    try {
                        new SnipOverlay((image, error) -> Platform.runLater(() -> {
                            restoreCaptureWindows(restoreOwner, restoreEditor);
                            PauseTransition restoreDelay = new PauseTransition(Duration.millis(180));
                            restoreDelay.setOnFinished(restoreEvent -> {
                                try {
                                    editorStage.toFront();
                                    if (error != null) reportCaptureFailure(error);
                                    else if (image != null) insertCapturedImage(image, "Captured key image");
                                    else setEditorStatus("Capture canceled.");
                                } catch (RuntimeException ex) {
                                    reportCaptureFailure(ex);
                                }
                            });
                            restoreDelay.play();
                        })).show();
                    } catch (RuntimeException ex) {
                        restoreCaptureWindows(restoreOwner, restoreEditor);
                        reportCaptureFailure(ex);
                    }
                });
                minimizeDelay.play();
            } catch (RuntimeException ex) {
                restoreCaptureWindows(restoreOwner, restoreEditor);
                reportCaptureFailure(ex);
            }
        }

        public void pasteKeyImage() {
            try {
                Image image = Clipboard.getSystemClipboard().getImage();
                if (image == null) {
                    setEditorStatus("No image found on the system clipboard. Copy an image, then try again.");
                    return;
                }
                insertCapturedImage(image, "Pasted key image");
            } catch (RuntimeException ex) {
                app.logger.error("Could not read an image from the system clipboard", ex);
                app.statusLabel.setText("COULD NOT READ IMAGE FROM CLIPBOARD: " + ex.getMessage());
                setEditorStatus("Could not read an image from the clipboard.");
            }
        }

        private void insertCapturedImage(Image image, String altText) {
            try {
                String dataUrl = app.imageToDataUrl(image);
                editorEngine.executeScript("insertImageDataUrl('"
                        + dataUrl + "','" + altText + "');");
            } catch (IOException ex) {
                app.logger.error("Could not encode the selected editor image", ex);
                setEditorStatus("Could not encode the selected image.");
                app.statusLabel.setText("COULD NOT ENCODE EDITOR IMAGE: " + ex.getMessage());
            } catch (RuntimeException ex) {
                app.logger.error("Could not insert the selected editor image", ex);
                setEditorStatus("Could not insert the selected image.");
                app.statusLabel.setText("COULD NOT INSERT EDITOR IMAGE: " + ex.getMessage());
            }
        }

        private void restoreCaptureWindows(boolean restoreOwner, boolean restoreEditor) {
            if (restoreOwner) {
                try {
                    ownerStage.setIconified(false);
                } catch (RuntimeException ex) {
                    app.logger.warn("Could not restore the main window after screen capture", ex);
                    app.statusLabel.setText("COULD NOT RESTORE MAIN WINDOW: " + ex.getMessage());
                }
            }
            if (restoreEditor) {
                try {
                    editorStage.setIconified(false);
                } catch (RuntimeException ex) {
                    app.logger.warn("Could not restore the editor window after screen capture", ex);
                    app.statusLabel.setText("COULD NOT RESTORE EDITOR WINDOW: " + ex.getMessage());
                }
            }
        }

        private void reportCaptureFailure(RuntimeException error) {
            app.logger.error("Could not capture a screen image", error);
            String detail = app.errorMessage(error);
            app.statusLabel.setText("SCREEN CAPTURE FAILED: " + detail);
            setEditorStatus("Screen capture failed: " + detail);
            app.showError("Screen Capture Error", "The screen region could not be captured.\n" + detail);
        }

        private void setEditorStatus(String message) {
            try {
                editorEngine.executeScript("document.getElementById('status').textContent = "
                        + JSONObject.quote(message) + ";");
            } catch (RuntimeException ex) {
                app.logger.warn("Could not update the report editor status", ex);
                app.statusLabel.setText(message);
            }
        }
    }

    private void handleZip(String itemName) {
        if (itemName == null || dicomDirectory == null) return;
        String clean = itemName.replace("✅ ", "").trim();
        File source = new File(dicomDirectory, clean);
        File archive = new File(dicomDirectory, clean + ".zip");
        new Thread(() -> {
            try (FileOutputStream output = new FileOutputStream(archive);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                if (source.isDirectory()) zipFolder(source, "", zip);
                else if (source.isFile()) zipFileEntry(source, zip);
                else throw new FileNotFoundException("The selected DICOM item no longer exists: " + source);
                Platform.runLater(this::refreshBrowserList);
            } catch (IOException e) {
                reportError("Archive Creation Error", "Could not create the ZIP archive.", e);
            }
        }, "dicom-archive").start();
    }
    private void zipFolder(File folder, String parent, ZipOutputStream zos) throws IOException { File[] files = folder.listFiles(); if (files != null) for (File f : files) { String name = parent.isEmpty() ? f.getName() : parent + "/" + f.getName(); if (f.isDirectory()) zipFolder(f, name, zos); else { zos.putNextEntry(new ZipEntry(name)); java.nio.file.Files.copy(f.toPath(), zos); zos.closeEntry(); } } }
    private void zipFileEntry(File file, ZipOutputStream zos) throws IOException { zos.putNextEntry(new ZipEntry(file.getName())); java.nio.file.Files.copy(file.toPath(), zos); zos.closeEntry(); }
    private void deleteFileRecursive(File f) { if (f.isDirectory()) { File[] sub = f.listFiles(); if (sub != null) for (File s : sub) deleteFileRecursive(s); } f.delete(); }
    private void setOutputFolder(Stage s) {
        DirectoryChooser chooser = new DirectoryChooser();
        File initialDirectory = outputDirectory != null && outputDirectory.isDirectory()
                ? outputDirectory
                : baseOutputDirectory;
        if (initialDirectory != null && initialDirectory.isDirectory()) {
            chooser.setInitialDirectory(initialDirectory);
        }
        File selected = chooser.showDialog(s);
        if (selected != null) {
            outputDirectory = selected;
            saveSettings();
            loadHistoryFromFolder();
            refreshBrowserList();
        }
    }

    private void showSettingsDialog(Stage ownerStage) {
        Dialog<ButtonType> dialog = new Dialog<>();
        applyDialogTheme(dialog);
        dialog.initOwner(ownerStage);
        dialog.setTitle("Application Settings");
        dialog.setHeaderText("Configure report output, DICOM browsing, and PACS connection.");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Label outputPath = new Label();
        Label dicomPath = new Label();
        Label pacsAddress = new Label();
        Label updateStatus = new Label("Updates are checked manually; installation is never automatic.");
        for (Label value : Arrays.asList(outputPath, dicomPath, pacsAddress, updateStatus)) {
            value.setWrapText(true);
            value.setMaxWidth(420);
            value.setStyle("-fx-text-fill: #555;");
        }
        Runnable refreshValues = () -> {
            outputPath.setText(outputDirectory == null ? "Not configured" : outputDirectory.getAbsolutePath());
            dicomPath.setText(dicomDirectory == null ? "Not configured" : dicomDirectory.getAbsolutePath());
            pacsAddress.setText(pacsUrl == null || pacsUrl.trim().isEmpty() ? "Not configured" : pacsUrl);
        };

        Button chooseOutput = new Button("Choose Output Folder");
        Button chooseDicom = new Button("Choose DICOM Folder");
        Button configurePacs = new Button("Configure PACS");
        Button checkUpdates = new Button("Check for Updates");
        chooseOutput.setOnAction(event -> {
            setOutputFolder(ownerStage);
            refreshValues.run();
        });
        chooseDicom.setOnAction(event -> {
            File selected = new DirectoryChooser().showDialog(ownerStage);
            if (selected != null) {
                navigateTo(selected);
                saveSettings();
            }
            refreshValues.run();
        });
        configurePacs.setOnAction(event -> {
            showPacsSettingsDialog(ownerStage);
            refreshValues.run();
        });
        checkUpdates.setOnAction(event -> checkForUpdates(checkUpdates, updateStatus));

        GridPane settings = new GridPane();
        settings.setHgap(12);
        settings.setVgap(14);
        settings.setPadding(new Insets(16));
        settings.add(chooseOutput, 0, 0);
        settings.add(outputPath, 1, 0);
        settings.add(chooseDicom, 0, 1);
        settings.add(dicomPath, 1, 1);
        settings.add(configurePacs, 0, 2);
        settings.add(pacsAddress, 1, 2);
        settings.add(checkUpdates, 0, 3);
        settings.add(updateStatus, 1, 3);
        GridPane.setHgrow(outputPath, Priority.ALWAYS);
        GridPane.setHgrow(dicomPath, Priority.ALWAYS);
        GridPane.setHgrow(pacsAddress, Priority.ALWAYS);
        GridPane.setHgrow(updateStatus, Priority.ALWAYS);
        dialog.getDialogPane().setContent(settings);
        dialog.getDialogPane().setMinWidth(600);
        refreshValues.run();
        dialog.show();
    }

    private void checkForUpdates(Button checkButton, Label updateStatus) {
        checkButton.setDisable(true);
        updateStatus.setText("Checking SourceForge for the latest Windows package...");
        ioExecutor.submit(() -> {
            try {
                SourceForgeReleaseService.WindowsRelease release = releaseService.checkForWindowsRelease();
                Platform.runLater(() -> {
                    checkButton.setDisable(false);
                    updateStatus.setText("Latest Windows package: " + release.filename()
                            + " (updated " + release.formattedModifiedAt() + ")");
                    ButtonType downloadButton = new ButtonType("Open Download", ButtonBar.ButtonData.OK_DONE);
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    applyDialogTheme(alert);
                    alert.setTitle("Windows Update");
                    alert.setHeaderText("A SourceForge Windows package is published.");
                    alert.setContentText("Package: " + release.filename()
                            + "\nUpdated: " + release.formattedModifiedAt()
                            + "\nSHA-256: " + release.sha256()
                            + "\n\nThe app will not download or install updates automatically.");
                    alert.getButtonTypes().setAll(downloadButton, ButtonType.CANCEL);
                    alert.showAndWait().filter(downloadButton::equals).ifPresent(button -> {
                        try {
                            Desktop.getDesktop().browse(release.downloadUri());
                        } catch (IOException | UnsupportedOperationException e) {
                            reportError("Update Download Error",
                                    "Could not open the verified SourceForge download page.", e);
                        }
                    });
                });
            } catch (IOException e) {
                logger.error("Could not check the SourceForge Windows release feed", e);
                Platform.runLater(() -> {
                    checkButton.setDisable(false);
                    updateStatus.setText("Update check failed.");
                    showError("Update Check Error",
                            "Could not retrieve or validate the SourceForge release information.\n"
                                    + errorMessage(e));
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("SourceForge release check was interrupted", e);
                Platform.runLater(() -> {
                    checkButton.setDisable(false);
                    updateStatus.setText("Update check was interrupted.");
                });
            }
        });
    }

    private void clearAll() { textArea.clear(); imgViewCurrent.setImage(null); imgViewPrior.setImage(null); cleanupMemory(); }
    private void copyTextWithFeedback() { if (textArea.getText().isEmpty()) return; ClipboardContent c = new ClipboardContent(); c.putString(textArea.getText()); Clipboard.getSystemClipboard().setContent(c); textArea.setStyle("-fx-text-fill: #00ff41;"); PauseTransition p = new PauseTransition(Duration.seconds(1)); p.setOnFinished(ev -> textArea.setStyle("")); p.play(); }
    private void copyImageToClipboard(Image img) {
        if (img == null) {
            statusLabel.setText("NO CAPTURED IMAGE TO COPY.");
            return;
        }
        try {
            ClipboardContent content = new ClipboardContent();
            content.putImage(img);
            if (!Clipboard.getSystemClipboard().setContent(content)) {
                statusLabel.setText("COULD NOT COPY THE IMAGE TO THE CLIPBOARD.");
                return;
            }
            statusLabel.setText("IMAGE COPIED TO THE CLIPBOARD.");
        } catch (RuntimeException ex) {
            logger.error("Could not copy the captured image to the clipboard", ex);
            statusLabel.setText("IMAGE COPY FAILED: " + ex.getMessage());
        }
    }
    private void saveSettings() {
    Properties p = new Properties(); 
    p.setProperty("theme", selectedTheme);
        if (outputDirectory != null) p.setProperty("output", outputDirectory.getAbsolutePath()); 
        if (baseOutputDirectory != null) p.setProperty("baseOutput", baseOutputDirectory.getAbsolutePath());
        if (lastTemplateDirectory != null) p.setProperty("templateDir", lastTemplateDirectory.getAbsolutePath()); 
        if (dicomDirectory != null) p.setProperty("dicomDir", dicomDirectory.getAbsolutePath()); 
        if (cbCenter.getValue() != null) p.setProperty("lastCenter", cbCenter.getValue());
        if (cbScanType.getValue() != null) p.setProperty("lastScan", cbScanType.getValue());
        p.setProperty("pacsUrl", pacsUrl);
        p.setProperty("pacsUsername", pacsUsername);
        p.setProperty("pacsPassword", pacsPassword);
        windowState.writeTo(p);
        try {
            settingsStore.save(p);
        } catch (IOException e) {
            logger.error("Could not save workstation settings to {}", settingsStore.settingsFile(), e);
            statusLabel.setText("SETTINGS SAVE FAILED: " + e.getMessage());
        }
    }
    
    private void saveLastReportPath(File reportFile) {
        if (reportFile == null || !reportFile.exists()) return;
        try {
            JSONObject json = new JSONObject();
            json.put("lastReportPath", reportFile.getAbsolutePath());
            json.put("lastReportName", reportFile.getName());
            json.put("lastModified", System.currentTimeMillis());
            try (FileWriter writer = new FileWriter(LAST_REPORT_JSON)) {
                writer.write(json.toString());
            }
        } catch (Exception e) {
            System.err.println("Could not save last report path: " + e.getMessage());
        }
    }
    
    private File loadLastReportPath() {
        File jsonFile = new File(LAST_REPORT_JSON);
        if (!jsonFile.exists()) return null;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(jsonFile.toPath()));
            JSONObject json = new JSONObject(content);
            String path = json.getString("lastReportPath");
            File reportFile = new File(path);
            if (reportFile.exists()) {
                return reportFile;
            }
        } catch (Exception e) {
            System.err.println("Could not load last report path: " + e.getMessage());
        }
        return null;
    }

    private void loadSettings() { 
        isLoadingSettings = true;
        try {
            Properties p = settingsStore.load();
            windowState = WindowState.from(p).fitToScreens(
                    Screen.getScreens().stream().map(Screen::getVisualBounds).toList());
            if (p.getProperty("output") != null) outputDirectory = new File(p.getProperty("output")); 
            if (p.getProperty("baseOutput") != null) baseOutputDirectory = new File(p.getProperty("baseOutput"));
            if (p.getProperty("templateDir") != null) lastTemplateDirectory = new File(p.getProperty("templateDir")); 
            if (p.getProperty("dicomDir") != null) dicomDirectory = new File(p.getProperty("dicomDir")); 
            if (p.getProperty("lastCenter") != null) cbCenter.setValue(p.getProperty("lastCenter"));
            if (p.getProperty("lastScan") != null) {
                cbScanType.setValue(p.getProperty("lastScan"));
                updatePriceForScan(cbScanType.getValue());
            }
            if (p.getProperty("pacsUrl") != null) pacsUrl = p.getProperty("pacsUrl");
            if (p.getProperty("pacsUsername") != null) pacsUsername = p.getProperty("pacsUsername");
            if (p.getProperty("pacsPassword") != null) pacsPassword = p.getProperty("pacsPassword");
            String savedTheme = p.getProperty("theme");
            if ("Classic Dark".equals(savedTheme) || "Visual Studio Dark".equals(savedTheme) || "Light".equals(savedTheme)) {
                selectedTheme = savedTheme;
            }
            if (settingsStore.isUsingLegacyFile()) {
                saveSettings();
            }
        } catch (IOException e) {
            logger.error("Could not read workstation settings from {}", settingsStore.settingsFile(), e);
        } catch (IllegalArgumentException e) {
            logger.error("Workstation settings contain invalid property data", e);
        } finally {
            isLoadingSettings = false;
        }
    }

    private void chooseTheme(Stage ownerStage, VBox root) {
        List<String> themes = Arrays.asList("Classic Dark", "Visual Studio Dark", "Light");
        ChoiceDialog<String> dialog = new ChoiceDialog<>(selectedTheme, themes);
        applyDialogTheme(dialog);
        dialog.initOwner(ownerStage);
        dialog.setTitle("Choose Theme");
        dialog.setHeaderText("Select an application theme");
        dialog.setContentText("Theme:");
        dialog.showAndWait().ifPresent(theme -> {
            selectedTheme = theme;
            applyTheme(root);
            saveSettings();
        });
    }

    private void applyTheme(VBox root) {
        root.getStyleClass().removeAll("theme-classic-dark", "theme-visual-studio", "theme-light");
        if ("Visual Studio Dark".equals(selectedTheme)) {
            root.getStyleClass().add("theme-visual-studio");
            datePicker.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-background-color: #333337; -fx-text-fill: #d4d4d4; -fx-border-color: #505055; -fx-border-width: 1; -fx-padding: 8 12 8 12;");
        } else if ("Light".equals(selectedTheme)) {
            root.getStyleClass().add("theme-light");
            datePicker.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-background-color: white; -fx-text-fill: #1f2937; -fx-border-color: #cbd5e1; -fx-border-width: 1; -fx-padding: 8 12 8 12;");
        } else {
            root.getStyleClass().add("theme-classic-dark");
            datePicker.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-background-color: #3a3a3a; -fx-text-fill: #e0e0e0; -fx-border-color: #555; -fx-border-width: 1; -fx-padding: 8 12 8 12;");
        }
    }

    private void initTesseract() {
        try {
            Path tessdataDirectory = Paths.get(System.getProperty("user.home"), ".eliterads", "tessdata");
            Files.createDirectories(tessdataDirectory);
            for (String language : Arrays.asList("eng", "ara")) {
                String resourceName = "/tessdata/" + language + ".traineddata";
                Path languageData = tessdataDirectory.resolve(language + ".traineddata");
                try (InputStream resource = App.class.getResourceAsStream(resourceName)) {
                    if (resource == null) {
                        throw new IOException("Missing OCR language resource: " + resourceName);
                    }
                    Files.copy(resource, languageData, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            tesseract.setDatapath(tessdataDirectory.toString());
            tesseractReady = true;
        } catch (Exception ex) {
            tesseractReady = false;
            logger.error("Could not initialize OCR resources", ex);
            statusLabel.setText("OCR INITIALIZATION FAILED: " + ex.getMessage());
        }
    }

    // PACS Settings Dialog
    private void showPacsSettingsDialog(Stage ownerStage) {
        Dialog<String[]> dialog = new Dialog<>();
        applyDialogTheme(dialog);
        dialog.setTitle("PACS Configuration");
        dialog.setHeaderText("Configure MillinSys PACS Connection");
        
        ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, ButtonType.CANCEL);
        
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(20, 10, 10, 10));
        
        TextField urlField = new TextField(pacsUrl);
        urlField.setPromptText("Enter PACS URL (e.g., http://localhost:8042/)");
        
        TextField userField = new TextField(pacsUsername);
        userField.setPromptText("Username (optional)");
        
        PasswordField passField = new PasswordField();
        passField.setText(pacsPassword);
        passField.setPromptText("Password (optional)");
        
        grid.add(new Label("PACS URL:"), 0, 0);
        grid.add(urlField, 1, 0);
        grid.add(new Label("Username:"), 0, 1);
        grid.add(userField, 1, 1);
        grid.add(new Label("Password:"), 0, 2);
        grid.add(passField, 1, 2);
        
        dialog.getDialogPane().setContent(grid);
        
        dialog.setResultConverter(b -> b == saveButton ? new String[]{urlField.getText(), userField.getText(), passField.getText()} : null);
        dialog.showAndWait().ifPresent(result -> {
            if (result != null && !result[0].trim().isEmpty()) {
                pacsUrl = result[0].trim();
                if (!pacsUrl.startsWith("http://") && !pacsUrl.startsWith("https://")) {
                    pacsUrl = "http://" + pacsUrl;
                }
                pacsUsername = result[1] != null ? result[1].trim() : "";
                pacsPassword = result[2] != null ? result[2].trim() : "";
                saveSettings();
                statusLabel.setText("PACS CONFIGURATION SAVED");
            }
        });
    }

    // PACS Browser - Opens MillinSys PACS system in a secondary window with tabs
    private void openPacsBrowser(Stage ownerStage) {
        Stage pacsStage = new Stage();
        pacsStage.initOwner(ownerStage);
        pacsStage.setTitle("PACS Browser - MillinSys");
        pacsStage.setMaximized(true);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        
        Tab mainTab = new Tab("PACS Main");
        mainTab.setClosable(false);
        WebView webView = createOptimizedWebView();
        final WebEngine webEngine = webView.getEngine();
        
        webEngine.setJavaScriptEnabled(true);
        webEngine.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        webEngine.setUserDataDirectory(new File(System.getProperty("user.home"), ".eliterads/pacs_session"));
        
        final List<Tab> tabs = new java.util.ArrayList<>();
        final List<WebView> webViews = new java.util.ArrayList<>();
        final TextField[] urlFieldHolder = new TextField[1];
        tabs.add(mainTab);
        webViews.add(webView);
        
        webEngine.setCreatePopupHandler(popupFeatures -> {
            WebView popupWebView = createOptimizedWebView();
            WebEngine popupEngine = popupWebView.getEngine();
            popupEngine.setJavaScriptEnabled(true);
            popupEngine.setUserAgent(webEngine.getUserAgent());
            popupEngine.setUserDataDirectory(new File(System.getProperty("user.home"), ".eliterads/pacs_session"));
            popupEngine.setCreatePopupHandler(popupFeatures2 -> popupEngine);

            Tab newTab = new Tab("PACS Study");
            newTab.setClosable(true);
            newTab.setContent(popupWebView);
            popupEngine.locationProperty().addListener((obs, oldUrl, newUrl) -> {
                if (newUrl != null && !newUrl.isEmpty() && tabPane.getSelectionModel().getSelectedItem() == newTab && urlFieldHolder[0] != null) {
                    urlFieldHolder[0].setText(newUrl);
                }
            });
            newTab.setOnClosed(event -> {
                tabs.remove(newTab);
                webViews.remove(popupWebView);
            });
            Platform.runLater(() -> {
                tabPane.getTabs().add(newTab);
                tabs.add(newTab);
                webViews.add(popupWebView);
                tabPane.getSelectionModel().select(newTab);
            });
            return popupEngine;
        });
        
        webEngine.locationProperty().addListener((obs, oldUrl, newUrl) -> {
            if (newUrl != null && !newUrl.isEmpty() && urlFieldHolder[0] != null) {
                urlFieldHolder[0].setText(newUrl);
            }
        });
        
        String urlToLoad = pacsUrl;
        if (!pacsUsername.isEmpty() && !pacsPassword.isEmpty()) {
            urlToLoad = pacsUrl.replace("://", "://" + pacsUsername + ":" + pacsPassword + "@");
        }
        webEngine.load(urlToLoad);

        Button btnBack = new Button("← Back");
        Button btnForward = new Button("Forward →");
        Button btnRefresh = new Button("🔄 Refresh");
        Button btnDownload = new Button("⬇️ Download");
        Button btnNewTab = new Button("➕ New Tab");
        TextField urlField = new TextField(pacsUrl);
        FlowPane downloadList = new FlowPane(8, 8);
        downloadList.setPadding(new Insets(3));
        ScrollPane downloadPane = new ScrollPane(downloadList);
        downloadPane.setFitToWidth(true);
        downloadPane.setFitToHeight(true);
        downloadPane.setMinHeight(35);
        downloadPane.setMaxHeight(150);
        downloadPane.setPrefHeight(35);
        downloadPane.setStyle("-fx-background-color: #2725257e; -fx-border-color: #3473e7; -fx-border-width: 1;-fx-background-insets: 0; -fx-border-insets: 0;");
        urlField.setPromptText("PACS URL will appear here...");
        urlFieldHolder[0] = urlField;

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, previousTab, selectedTab) -> {
            int selectedIndex = tabPane.getTabs().indexOf(selectedTab);
            if (selectedIndex >= 0 && selectedIndex < webViews.size()) {
                String selectedUrl = webViews.get(selectedIndex).getEngine().getLocation();
                urlField.setText(selectedUrl == null ? "" : selectedUrl);
            }
        });

        btnBack.setOnAction(e -> {
            int selectedIndex = tabPane.getSelectionModel().getSelectedIndex();
            if (selectedIndex < 0 || selectedIndex >= webViews.size()) return;
            WebHistory history = webViews.get(selectedIndex).getEngine().getHistory();
            if (history.getCurrentIndex() > 0) {
                history.go(-1);
            }
        });
        btnForward.setOnAction(e -> {
            int selectedIndex = tabPane.getSelectionModel().getSelectedIndex();
            if (selectedIndex < 0 || selectedIndex >= webViews.size()) return;
            WebHistory history = webViews.get(selectedIndex).getEngine().getHistory();
            try {
                if (history.getCurrentIndex() < history.getEntries().size() - 1) {
                    history.go(1);
                }
            } catch (Exception ex) {
            }
        });
        btnRefresh.setOnAction(e -> {
            int selectedIndex = tabPane.getSelectionModel().getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < webViews.size()) {
                webViews.get(selectedIndex).getEngine().reload();
            }
        });
        
        btnDownload.setOnAction(e -> {
            int selectedIndex = tabPane.getSelectionModel().getSelectedIndex();
            if (selectedIndex < 0 || selectedIndex >= webViews.size()) {
                showError("Download", "Select a PACS study tab first.");
                return;
            }
            WebEngine selectedEngine = webViews.get(selectedIndex).getEngine();
            String selectedUrl = selectedEngine.getLocation();
            if (isPacsDownloadUrl(selectedUrl)) {
                if (dicomDirectory == null || !dicomDirectory.isDirectory()) {
                    showError("Download", "Set a valid DICOM folder in the main application before downloading a study.");
                    return;
                }
                File target = createStudyDownloadTarget(pacsStage, selectedUrl);
                if (target != null) downloadPacsStudy(selectedUrl, target, downloadList);
                return;
            }
            String downloadScript = "(function() {" + "var direct = document.querySelector('a[href*=" + "\\\"download\\\"" + "],a[href*=" + "\\\"export\\\"" + "],a[href*=" + "\\\"dicom\\\"" + "],a[href$=" + "\\\".zip\\\"" + "],a[href$=" + "\\\".dcm\\\"" + "],[data-download-url]');" + "if (direct) return direct.href || direct.getAttribute('data-download-url') || '';" + "var controls = Array.prototype.slice.call(document.querySelectorAll('a,button,input,[role=" + "\\\"button\\\"" + "],[onclick],[title],[aria-label]'));" + "var item = controls.find(function(el) {" + "  var text = [el.innerText, el.value, el.title, el.getAttribute('aria-label'), el.getAttribute('onclick'), el.className].join(' ').toLowerCase();" + "  return el.offsetParent !== null && /(download|export|dicom|save study|save series|zip)/.test(text);" + "});" + "if (!item) return '';" + "var url = item.href || item.getAttribute('data-download-url');" + "if (url) return url;" + "item.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window})); item.click(); return 'CLICKED';" + "})();";
            try {
                Object result = selectedEngine.executeScript(downloadScript);
                String downloadUrl = result == null ? "" : result.toString();
                if (downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://")) {
                    FileChooser chooser = new FileChooser();
                    chooser.setTitle("Save PACS Study");
                    chooser.setInitialFileName("PACS-study.zip");
                    File target = chooser.showSaveDialog(pacsStage);
                    if (target != null) downloadPacsStudy(downloadUrl, target, downloadList);
                } else if ("CLICKED".equals(downloadUrl)) {
                    showInfo("PACS download", "The download command was sent to the selected PACS study.");
                } else {
                    showError("Download", "No study download link was found. Select a study in PACS first.");
                }
            } catch (Exception ex) {
                showError("Download", "Could not start the study download: " + ex.getMessage());
            }
        });
        
        btnNewTab.setOnAction(e -> {
            Tab newTab = new Tab("New Tab " + (tabs.size() + 1));
            WebView newWebView = createOptimizedWebView();
            WebEngine newEngine = newWebView.getEngine();
            newEngine.setJavaScriptEnabled(true);
            newEngine.setUserAgent(webEngine.getUserAgent());
            newEngine.setUserDataDirectory(new File(System.getProperty("user.home"), ".eliterads/pacs_session"));
            
            newEngine.setCreatePopupHandler(popupFeatures2 -> {
                return newEngine;
            });
            
            newEngine.locationProperty().addListener((obs, oldUrl, newUrl) -> {
                if (newUrl != null && !newUrl.isEmpty() && tabPane.getSelectionModel().getSelectedItem() == newTab) {
                    urlFieldHolder[0].setText(newUrl);
                }
            });
            
            newTab.setContent(newWebView);
            newTab.setClosable(true);
            tabPane.getTabs().add(newTab);
            tabPane.getSelectionModel().select(newTab);
            tabs.add(newTab);
            webViews.add(newWebView);
            newTab.setOnClosed(event -> {
                int index = tabs.indexOf(newTab);
                if (index >= 0) {
                    tabs.remove(index);
                    webViews.remove(index);
                }
            });
        });
        
        urlField.setOnAction(e -> {
            String url = urlField.getText();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            int selectedIndex = tabPane.getSelectionModel().getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < webViews.size()) {
                webViews.get(selectedIndex).getEngine().load(url);
            }
        });

        HBox navBar = new HBox(5, btnBack, btnForward, btnRefresh, urlField, btnDownload, btnNewTab);
        HBox.setHgrow(urlField, Priority.ALWAYS);
        navBar.setPadding(new Insets(5));
        navBar.setStyle("-fx-background-color: #2a2a2a; -fx-border-color: #444; -fx-border-width: 0 0 1 0;");
        
        String navButtonStyle = "-fx-background-color: #3a3a3a; -fx-text-fill: #e0e0e0; -fx-border-color: #555; -fx-border-width: 1; -fx-padding: 4 8 4 8; -fx-border-radius: 6; -fx-font-size: 14px;";
        btnBack.setStyle(navButtonStyle);
        btnForward.setStyle(navButtonStyle);
        btnRefresh.setStyle(navButtonStyle);
        btnDownload.setStyle(navButtonStyle);
        btnNewTab.setStyle(navButtonStyle);
        urlField.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: #e0e0e0; -fx-border-color: #555; -fx-prompt-text-fill: #888;");

        mainTab.setContent(webView);
        tabPane.getTabs().add(mainTab);

        VBox root = new VBox(navBar, downloadPane, tabPane);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 1280, 800);
        scene.setFill(Color.web("#1a1a1a"));
        pacsStage.setScene(scene);
        
        pacsStage.setOnCloseRequest(e -> {
            cleanupWebViews(webViews);
        });
        
        pacsStage.show();
    }
    
    // Helper method to create optimized WebView with cookie support
    private WebView createOptimizedWebView() {
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();
        
        engine.setJavaScriptEnabled(true);
        
        // Enable persistent cookie storage for session management
        File cookieDir = new File(System.getProperty("user.home"), ".eliterads/cookies");
        cookieDir.mkdirs();
        engine.setUserDataDirectory(cookieDir);
        
        engine.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        
        webView.setContextMenuEnabled(true);
        
        return webView;
    }
    
    // Ultra-fast WebView optimized for AI tools
    private WebView createUltraFastWebView() {
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();
        
        // Enable JavaScript
        engine.setJavaScriptEnabled(true);
        
        // Ultra-fast settings
        System.setProperty("javafx.webkit.cacheEnabled", "true");
        System.setProperty("javafx.webkit.maxCacheSize", "100");
        System.setProperty("javafx.webkit.threadModel", "single");
        System.setProperty("prism.lcdtext", "false");
        System.setProperty("prism.text", "t2k");
        System.setProperty("javafx.animation.pulse", "60");
        
        // This view renders only local assistant guidance. It has no persistent
        // browsing profile, cookies, or authentication state.
        webView.setContextMenuEnabled(false);
        
        return webView;
    }
    
    // Cleanup WebViews to free memory
    private void cleanupWebViews(List<WebView> webViews) {
        for (WebView webView : webViews) {
            try {
                if (webView != null && webView.getEngine() != null) {
                    webView.getEngine().loadContent("");
                }
            } catch (RuntimeException e) {
                logger.warn("Could not clear a WebView during cleanup", e);
            }
        }
        webViews.clear();
    }
    
    // Cleanup method to free memory
    private void cleanupMemory() {
        browserListCache.clear();
        historyCache.clear();
        reportedFilesCache.clear();
        
        System.gc();
    }

    private void downloadPacsStudy(String downloadUrl, File destination, FlowPane downloadList) {
        ProgressBar progressBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setPrefWidth(150);
        progressBar.setMaxHeight(20);
        
        Label progressLabel = new Label("Starting...");
        progressLabel.setStyle("-fx-font-size: 11px;");
        
        Button btnPause = new Button("⏸️");
        btnPause.setStyle("-fx-font-size: 12px; -fx-padding: 2 5 2 5;");
        Button btnResume = new Button("▶️");
        btnResume.setStyle("-fx-font-size: 12px; -fx-padding: 2 5 2 5;");
        btnResume.setDisable(true);
        Button btnCancel = new Button("❌");
        btnCancel.setStyle("-fx-font-size: 12px; -fx-padding: 2 5 2 5;");
        
        HBox downloadRow = new HBox(5, progressBar, progressLabel, btnPause, btnResume, btnCancel);
        downloadRow.setAlignment(Pos.CENTER_LEFT);
        downloadRow.setPadding(new Insets(2, 5, 2, 5));
        downloadRow.setStyle("-fx-background-color: #eb7b7b; -fx-border-color: #ddd; -fx-border-width: 1; -fx-border-radius: 3;");
        downloadList.getChildren().add(downloadRow);
        
        final java.util.concurrent.atomic.AtomicReference<HttpURLConnection> connectionRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<InputStream> inputStreamRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<OutputStream> outputStreamRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean isPaused = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean isCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicLong downloadedBytes = new java.util.concurrent.atomic.AtomicLong(0);
        File partialDestination = new File(destination.getAbsolutePath() + ".part");
        
        btnPause.setOnAction(e -> {
            isPaused.set(true);
            btnPause.setDisable(true);
            btnResume.setDisable(false);
            progressLabel.setText("Paused");
        });
        
        btnResume.setOnAction(e -> {
            isPaused.set(false);
            btnPause.setDisable(false);
            btnResume.setDisable(true);
            progressLabel.setText("Resuming...");
            synchronized (downloadRow) {
                downloadRow.notifyAll();
            }
        });
        
        btnCancel.setOnAction(e -> {
            isCancelled.set(true);
            btnCancel.setDisable(true);
            btnPause.setDisable(true);
            btnResume.setDisable(true);
            progressLabel.setText("Cancelling...");
            try {
                if (inputStreamRef.get() != null) inputStreamRef.get().close();
                if (outputStreamRef.get() != null) outputStreamRef.get().close();
                if (connectionRef.get() != null) connectionRef.get().disconnect();
            } catch (IOException ex) {
                logger.warn("Could not close the active PACS download during cancellation", ex);
            }
        });
        
        new Thread(() -> {
            try {
                Platform.runLater(() -> {
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    progressLabel.setText("Connecting...");
                });
                
                HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(120000);
                connection.setRequestProperty("User-Agent", "EliteradsPro/1.0");
                if (!pacsUsername.isEmpty() && !pacsPassword.isEmpty()) {
                    String credentials = pacsUsername + ":" + pacsPassword;
                    String encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    connection.setRequestProperty("Authorization", "Basic " + encoded);
                }
                connectionRef.set(connection);
                
                int responseCode = connection.getResponseCode();
                if (responseCode >= 400) throw new IOException("PACS returned HTTP " + responseCode);
                long totalBytes = connection.getContentLengthLong();
                
                try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(partialDestination)) {
                    inputStreamRef.set(input);
                    outputStreamRef.set(output);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    
                    while ((bytesRead = input.read(buffer)) != -1) {
                        if (isCancelled.get()) {
                            Platform.runLater(() -> {
                                progressBar.setProgress(0);
                                progressLabel.setText("Cancelled");
                                downloadRow.setStyle("-fx-background-color: #ffebee; -fx-border-color: #f44336;");
                            });
                            break;
                        }
                        
                        while (isPaused.get() && !isCancelled.get()) {
                            synchronized (downloadRow) {
                                downloadRow.wait(100);
                            }
                        }
                        
                        if (isCancelled.get()) {
                            Platform.runLater(() -> {
                                progressBar.setProgress(0);
                                progressLabel.setText("Cancelled");
                                downloadRow.setStyle("-fx-background-color: #ffebee; -fx-border-color: #f44336;");
                            });
                            break;
                        }
                        
                        output.write(buffer, 0, bytesRead);
                        downloadedBytes.addAndGet(bytesRead);
                        final long currentBytes = downloadedBytes.get();
                        
                        Platform.runLater(() -> {
                            if (totalBytes > 0) {
                                double progress = Math.min(1.0, (double) currentBytes / totalBytes);
                                progressBar.setProgress(progress);
                                progressLabel.setText(String.format("%.0f%%", progress * 100));
                            } else {
                                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                                progressLabel.setText((currentBytes / 1024) + " KB");
                            }
                        });
                    }
                } finally {
                    connection.disconnect();
                }
                
                if (isCancelled.get()) {
                    deletePartialDownload(partialDestination);
                    Platform.runLater(() -> {
                        progressBar.setProgress(0);
                        progressLabel.setText("Cancelled");
                        downloadRow.setStyle("-fx-background-color: #ffebee; -fx-border-color: #f44336;");
                    });
                    return;
                }
                Files.move(partialDestination.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Platform.runLater(() -> {
                    progressBar.setProgress(1);
                    progressLabel.setText("✓ Complete");
                    downloadRow.setStyle("-fx-background-color: #e8f5e9; -fx-border-color: #4caf50;");
                    btnPause.setDisable(true);
                    btnResume.setDisable(true);
                    btnCancel.setDisable(true);
                });
            } catch (Exception ex) {
                deletePartialDownload(partialDestination);
                if (isCancelled.get()) return;
                logger.error("Could not download the PACS study to {}", destination, ex);
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    progressLabel.setText("Failed: " + errorMessage(ex));
                    downloadRow.setStyle("-fx-background-color: #ffebee; -fx-border-color: #f44336;");
                    btnPause.setDisable(true);
                    btnResume.setDisable(true);
                });
            }
        }, "pacs-study-download").start();
    }

    private void deletePartialDownload(File partialFile) {
        try {
            Files.deleteIfExists(partialFile.toPath());
        } catch (IOException e) {
            logger.error("Could not remove incomplete PACS download {}", partialFile, e);
            Platform.runLater(() -> statusLabel.setText("INCOMPLETE DOWNLOAD COULD NOT BE REMOVED: "
                    + partialFile.getAbsolutePath()));
        }
    }

    private boolean isPacsDownloadUrl(String url) {
        if (url == null) return false;
        String normalized = url.toLowerCase();
        return normalized.contains("downloadstudyext")
                || normalized.contains("downloadstudy")
                || normalized.contains("/download")
                || normalized.contains("/export");
    }

    private File createStudyDownloadTarget(Stage ownerStage, String downloadUrl) {
        Dialog<ButtonType> dialog = new Dialog<>();
        applyDialogTheme(dialog);
        dialog.initOwner(ownerStage);
        dialog.setTitle("Study download details");
        dialog.setHeaderText("Confirm the details used to name the DICOM study file.");
        ButtonType downloadButton = new ButtonType("Download", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(downloadButton, ButtonType.CANCEL);

        TextField studyIdField = new TextField(extractStudyId(downloadUrl));
        TextField patientNameField = new TextField();
        patientNameField.setPromptText("Patient name");
        DatePicker studyDatePicker = new DatePicker(LocalDate.now());
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 10, 10, 10));
        grid.addRow(0, new Label("Study ID:"), studyIdField);
        grid.addRow(1, new Label("Patient name:"), patientNameField);
        grid.addRow(2, new Label("Study date:"), studyDatePicker);
        dialog.getDialogPane().setContent(grid);

        java.util.Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != downloadButton) return null;
        String studyId = sanitizeFilePart(studyIdField.getText(), "PACS");
        String patientName = sanitizeFilePart(patientNameField.getText(), "UnknownPatient");
        LocalDate studyDate = studyDatePicker.getValue() == null ? LocalDate.now() : studyDatePicker.getValue();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        return new File(dicomDirectory, "Study_" + studyId + "_" + patientName + "_" + studyDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + "_" + timestamp + ".zip");
    }

    private String sanitizeFilePart(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim().replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private void applyDarkThemeToWebView(WebView webView) {
        if (webView == null || webView.getEngine() == null) return;
        WebEngine engine = webView.getEngine();
        engine.setUserStyleSheetLocation(getClass().getResource("/webview-dark-theme.css").toExternalForm());
    }
    
    private void openAiAssistant(Stage ownerStage) {
        openAiAssistantWithFile(ownerStage, null);
    }

    /** Opens the official ChatGPT HTTPS endpoint in the user's normal browser. */
    private void openChatGptSecurely() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new UnsupportedOperationException("No supported web browser is available.");
            }
            Desktop.getDesktop().browse(CHATGPT_URI);
            statusLabel.setText("ChatGPT opened securely in your browser.");
        } catch (Exception ex) {
            showError("Browser Error", "Could not open ChatGPT: " + ex.getMessage());
        }
    }

    private void ensureReportTemplatesTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS report_templates (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "section TEXT NOT NULL, modality TEXT NOT NULL, title TEXT NOT NULL, " +
                    "content_html TEXT NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private void ensureReportSignaturesTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS report_signatures (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, signature_text TEXT NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private List<ReportSignature> loadReportSignatures() throws SQLException {
        List<ReportSignature> signatures = new ArrayList<>();
        try (Connection connection = getConnection()) {
            ensureReportSignaturesTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, title, signature_text FROM report_signatures ORDER BY title");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    signatures.add(new ReportSignature(result.getInt("id"),
                            result.getString("title"), result.getString("signature_text")));
                }
            }
        }
        return signatures;
    }

    private ReportSignature saveReportSignature(ReportSignature signature) throws SQLException {
        try (Connection connection = getConnection()) {
            ensureReportSignaturesTable(connection);
            if (signature.getId() > 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE report_signatures SET title = ?, signature_text = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    statement.setString(1, signature.getTitle());
                    statement.setString(2, signature.getText());
                    statement.setInt(3, signature.getId());
                    if (statement.executeUpdate() == 0) throw new SQLException("The selected signature no longer exists.");
                }
                return signature;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO report_signatures (title, signature_text) VALUES (?, ?)")) {
                statement.setString(1, signature.getTitle());
                statement.setString(2, signature.getText());
                statement.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
                if (result.next()) return new ReportSignature(result.getInt(1), signature.getTitle(), signature.getText());
            }
        }
        throw new SQLException("Could not retrieve the new signature ID.");
    }

    private void deleteReportSignature(int id) throws SQLException {
        try (Connection connection = getConnection()) {
            ensureReportSignaturesTable(connection);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM report_signatures WHERE id = ?")) {
                statement.setInt(1, id);
                if (statement.executeUpdate() == 0) throw new SQLException("The selected signature no longer exists.");
            }
        }
    }

    private ReportSignature promptForReportSignature(Stage owner, ReportSignature existing) {
        Dialog<ReportSignature> dialog = new Dialog<>();
        applyDialogTheme(dialog);
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "Add Saved Signature" : "Edit Saved Signature");
        dialog.setHeaderText("Save the signature block to the workstation database.");
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField titleField = new TextField(existing == null ? "" : existing.getTitle());
        titleField.setPromptText("e.g., Primary Radiologist");
        TextArea signatureField = new TextArea(existing == null ? "" : existing.getText());
        signatureField.setPromptText("Name, credentials, registration number, or other approved signature text");
        signatureField.setWrapText(true);
        signatureField.setPrefRowCount(6);
        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(8);
        fields.setPadding(new Insets(12));
        fields.add(new Label("Signature title:"), 0, 0);
        fields.add(titleField, 1, 0);
        fields.add(new Label("Signature text:"), 0, 1);
        fields.add(signatureField, 1, 1);
        GridPane.setHgrow(titleField, Priority.ALWAYS);
        GridPane.setHgrow(signatureField, Priority.ALWAYS);
        signatureField.setPrefWidth(360);
        dialog.getDialogPane().setContent(fields);

        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (titleField.getText().trim().isEmpty() || signatureField.getText().trim().isEmpty()) {
                showError("Signature Required", "Enter both a signature title and signature text.");
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == saveType
                ? new ReportSignature(existing == null ? 0 : existing.getId(),
                        titleField.getText().trim(), signatureField.getText().trim())
                : null);
        return dialog.showAndWait().orElse(null);
    }

    private void insertTextAtSavedEditorSelection(WebEngine editorEngine, String text) {
        editorEngine.executeScript("insertTextAtSavedSelection(" + JSONObject.quote(text) + ");");
    }

    private List<ReportTemplate> loadReportTemplates() throws SQLException {
        List<ReportTemplate> templates = new ArrayList<>();
        try (Connection connection = getConnection()) {
            ensureReportTemplatesTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, section, modality, title, content_html FROM report_templates ORDER BY section, modality, title");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    templates.add(new ReportTemplate(
                            result.getInt("id"),
                            result.getString("section"),
                            result.getString("modality"),
                            result.getString("title"),
                            result.getString("content_html")));
                }
            }
        }
        return templates;
    }

    private TemplateImportSummary importReportTemplates(File sourceFile) throws SQLException {
        SQLiteConfig sourceConfig = new SQLiteConfig();
        sourceConfig.setReadOnly(true);
        List<ReportTemplate> sourceTemplates = new ArrayList<>();
        String sourceUrl = "jdbc:sqlite:" + sourceFile.getAbsolutePath();
        try (Connection sourceConnection = DriverManager.getConnection(sourceUrl, sourceConfig.toProperties());
             PreparedStatement statement = sourceConnection.prepareStatement(
                     "SELECT section, modality, title, content_html FROM report_templates " +
                             "ORDER BY section, modality, title");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String section = result.getString("section");
                String modality = result.getString("modality");
                String title = result.getString("title");
                String contentHtml = result.getString("content_html");
                if (section == null || section.trim().isEmpty()
                        || modality == null || modality.trim().isEmpty()
                        || title == null || title.trim().isEmpty()
                        || contentHtml == null) {
                    throw new SQLException("The source database contains a template with missing required fields.");
                }
                sourceTemplates.add(new ReportTemplate(0, section, modality, title, contentHtml));
            }
        }

        int imported = 0;
        int skipped = 0;
        try (Connection targetConnection = getConnection()) {
            ensureReportTemplatesTable(targetConnection);
            targetConnection.setAutoCommit(false);
            try {
                java.util.Set<String> existingKeys = new java.util.HashSet<>();
                try (PreparedStatement statement = targetConnection.prepareStatement(
                        "SELECT section, modality, title FROM report_templates");
                     ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        existingKeys.add(templateImportKey(result.getString("section"),
                                result.getString("modality"), result.getString("title")));
                    }
                }

                try (PreparedStatement statement = targetConnection.prepareStatement(
                        "INSERT INTO report_templates (section, modality, title, content_html) VALUES (?, ?, ?, ?)")) {
                    for (ReportTemplate template : sourceTemplates) {
                        String key = templateImportKey(template.getSection(), template.getModality(), template.getTitle());
                        if (!existingKeys.add(key)) {
                            skipped++;
                            continue;
                        }
                        statement.setString(1, template.getSection());
                        statement.setString(2, template.getModality());
                        statement.setString(3, template.getTitle());
                        statement.setString(4, template.getContentHtml());
                        statement.addBatch();
                        imported++;
                    }
                    statement.executeBatch();
                }
                targetConnection.commit();
            } catch (SQLException ex) {
                try {
                    targetConnection.rollback();
                } catch (SQLException rollbackException) {
                    ex.addSuppressed(rollbackException);
                }
                throw ex;
            }
        }
        return new TemplateImportSummary(imported, skipped);
    }

    private String templateImportKey(String section, String modality, String title) {
        return (section == null ? "" : section.trim().toLowerCase(java.util.Locale.ROOT)) + "\u0000"
                + (modality == null ? "" : modality.trim().toLowerCase(java.util.Locale.ROOT)) + "\u0000"
                + (title == null ? "" : title.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private ReportTemplate saveReportTemplate(ReportTemplate template) throws SQLException {
        try (Connection connection = getConnection()) {
            ensureReportTemplatesTable(connection);
            if (template.getId() > 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE report_templates SET section = ?, modality = ?, title = ?, content_html = ?, " +
                                "updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    statement.setString(1, template.getSection());
                    statement.setString(2, template.getModality());
                    statement.setString(3, template.getTitle());
                    statement.setString(4, template.getContentHtml());
                    statement.setInt(5, template.getId());
                    if (statement.executeUpdate() == 0) {
                        throw new SQLException("The selected template no longer exists.");
                    }
                }
                return template;
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO report_templates (section, modality, title, content_html) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, template.getSection());
                statement.setString(2, template.getModality());
                statement.setString(3, template.getTitle());
                statement.setString(4, template.getContentHtml());
                statement.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
                if (result.next()) {
                    return new ReportTemplate(result.getInt(1), template.getSection(),
                            template.getModality(), template.getTitle(), template.getContentHtml());
                }
            }
        }
        throw new SQLException("Could not retrieve the new template ID.");
    }

    private boolean containsIgnoreCase(String value, String query) {
        return query == null || query.trim().isEmpty()
                || value.toLowerCase(java.util.Locale.ROOT).contains(query.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private void deleteReportTemplate(int id) throws SQLException {
        try (Connection connection = getConnection()) {
            ensureReportTemplatesTable(connection);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM report_templates WHERE id = ?")) {
                statement.setInt(1, id);
                if (statement.executeUpdate() == 0) {
                    throw new SQLException("The selected template no longer exists.");
                }
            }
        }
    }

    private ReportTemplate promptForReportTemplate(Stage owner, ReportTemplate existing, String contentHtml) {
        Dialog<ReportTemplate> dialog = new Dialog<>();
        applyDialogTheme(dialog);
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "Save Report Template" : "Update Report Template");
        dialog.setHeaderText("Organize this report by body section, modality, and template title.");
        ButtonType saveType = new ButtonType("Save Template", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField sectionField = new TextField(existing == null ? "" : existing.getSection());
        sectionField.setPromptText("e.g., Brain, Spine, Chest");
        TextField modalityField = new TextField(existing == null ? "" : existing.getModality());
        modalityField.setPromptText("e.g., CT, MRI, X-ray, Ultrasound");
        TextField titleField = new TextField(existing == null ? "" : existing.getTitle());
        titleField.setPromptText("e.g., Normal Brain MRI");

        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(10);
        fields.setPadding(new Insets(12));
        fields.add(new Label("Section / body part:"), 0, 0);
        fields.add(sectionField, 1, 0);
        fields.add(new Label("Modality:"), 0, 1);
        fields.add(modalityField, 1, 1);
        fields.add(new Label("Template title:"), 0, 2);
        fields.add(titleField, 1, 2);
        GridPane.setHgrow(sectionField, Priority.ALWAYS);
        GridPane.setHgrow(modalityField, Priority.ALWAYS);
        GridPane.setHgrow(titleField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(fields);

        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (sectionField.getText().trim().isEmpty()
                    || modalityField.getText().trim().isEmpty()
                    || titleField.getText().trim().isEmpty()) {
                showError("Template Details Required", "Enter a section, modality, and template title.");
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == saveType
                ? new ReportTemplate(existing == null ? 0 : existing.getId(),
                        sectionField.getText().trim(), modalityField.getText().trim(),
                        titleField.getText().trim(), contentHtml)
                : null);
        return dialog.showAndWait().orElse(null);
    }

    private Path getReportingShortcutsPath() {
        return Paths.get(System.getProperty("user.home"), ".eliterads", "reporting-shortcuts.json");
    }

    private List<ReportShortcut> loadReportingShortcuts() throws IOException {
        Path file = getReportingShortcutsPath();
        List<ReportShortcut> shortcuts = new ArrayList<>();
        if (!Files.exists(file)) return shortcuts;

        JSONArray entries = new JSONArray(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            // Older shortcut files contained only title and phrase.
            String section = entry.optString("section", "General").trim();
            String modality = entry.optString("modality", "All").trim();
            String title = entry.getString("title").trim();
            String phrase = entry.getString("phrase").trim();
            if (section.isEmpty() || modality.isEmpty() || title.isEmpty() || phrase.isEmpty()) {
                throw new IOException("Shortcut " + (i + 1) + " must have a section, modality, title, and phrase.");
            }
            shortcuts.add(new ReportShortcut(section, modality, title, phrase));
        }
        return shortcuts;
    }

    private void saveReportingShortcuts(List<ReportShortcut> shortcuts) throws IOException {
        Path file = getReportingShortcutsPath();
        Files.createDirectories(file.getParent());
        JSONArray entries = new JSONArray();
        for (ReportShortcut shortcut : shortcuts) {
            JSONObject entry = new JSONObject();
            entry.put("section", shortcut.getSection());
            entry.put("modality", shortcut.getModality());
            entry.put("title", shortcut.getTitle());
            entry.put("phrase", shortcut.getPhrase());
            entries.put(entry);
        }

        Path temporaryFile = Files.createTempFile(file.getParent(), "reporting-shortcuts-", ".tmp");
        try {
            Files.write(temporaryFile, entries.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private ReportShortcut promptForReportingShortcut(Stage owner, ReportShortcut existing) {
        Dialog<ReportShortcut> dialog = new Dialog<>();
        applyDialogTheme(dialog);
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "Add Reporting Shortcut" : "Edit Reporting Shortcut");
        dialog.setHeaderText(existing == null ? "Create a reusable reporting phrase" : "Update this reporting phrase");

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField sectionField = new TextField(existing == null ? "" : existing.getSection());
        sectionField.setPromptText("e.g., Findings, Impression, Technique");
        TextField modalityField = new TextField(existing == null ? "" : existing.getModality());
        modalityField.setPromptText("e.g., CT, MRI, X-ray, Ultrasound");
        TextField titleField = new TextField(existing == null ? "" : existing.getTitle());
        titleField.setPromptText("e.g., Normal findings");
        TextArea phraseField = new TextArea(existing == null ? "" : existing.getPhrase());
        phraseField.setPromptText("Enter the sentence or phrase to insert into the report");
        phraseField.setWrapText(true);
        phraseField.setPrefRowCount(6);

        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(8);
        fields.setPadding(new Insets(10));
        fields.add(new Label("Section:"), 0, 0);
        fields.add(sectionField, 1, 0);
        fields.add(new Label("Modality:"), 0, 1);
        fields.add(modalityField, 1, 1);
        fields.add(new Label("Title:"), 0, 2);
        fields.add(titleField, 1, 2);
        fields.add(new Label("Phrase:"), 0, 3);
        fields.add(phraseField, 1, 3);
        GridPane.setHgrow(sectionField, Priority.ALWAYS);
        GridPane.setHgrow(modalityField, Priority.ALWAYS);
        GridPane.setHgrow(titleField, Priority.ALWAYS);
        GridPane.setHgrow(phraseField, Priority.ALWAYS);
        phraseField.setPrefWidth(360);
        dialog.getDialogPane().setContent(fields);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (sectionField.getText().trim().isEmpty()
                    || modalityField.getText().trim().isEmpty()
                    || titleField.getText().trim().isEmpty()
                    || phraseField.getText().trim().isEmpty()) {
                showError("Shortcut Required", "Enter a section, modality, title, and phrase.");
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == saveType
                ? new ReportShortcut(sectionField.getText().trim(), modalityField.getText().trim(),
                        titleField.getText().trim(), phraseField.getText().trim())
                : null);
        return dialog.showAndWait().orElse(null);
    }

    private void persistReportingShortcuts(List<ReportShortcut> updated,
                                           ObservableList<ReportShortcut> visibleShortcuts) throws IOException {
        saveReportingShortcuts(updated);
        visibleShortcuts.setAll(updated);
    }

    private void insertReportingShortcut(WebEngine editorEngine, ReportShortcut shortcut) {
        if (shortcut == null) return;
        insertTextAtSavedEditorSelection(editorEngine, shortcut.getPhrase());
        statusLabel.setText("INSERTED SHORTCUT: " + shortcut.getTitle());
    }
    
    private void openAiAssistantWithFile(Stage ownerStage, File fileToOpen) {
        Stage aiStage = new Stage();
        aiStage.setTitle("AI Assistant – Report Editor");

        WebView editorWebView = createOptimizedWebView();
        WebEngine editorEngine = editorWebView.getEngine();
        editorEngine.setJavaScriptEnabled(true);
        editorEngine.setConfirmHandler(message -> {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
            applyDialogTheme(confirmation);
            confirmation.initOwner(aiStage);
            confirmation.setTitle("Confirm Report Change");
            confirmation.setHeaderText(null);
            return confirmation.showAndWait().filter(ButtonType.OK::equals).isPresent();
        });
        editorEngine.setPromptHandler(prompt -> {
            TextInputDialog input = new TextInputDialog();
            applyDialogTheme(input);
            input.initOwner(aiStage);
            input.setTitle("Report Editor");
            input.setHeaderText(prompt.getMessage());
            input.setContentText("");
            return input.showAndWait().orElse(null);
        });
        
        String editorHtml = "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "<meta charset=\"UTF-8\">\n" +
            "<style>\n" +
            "  * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
            "  body { font-family: 'Segoe UI', Arial, sans-serif; background: linear-gradient(135deg, #202124 0%, #f6f3f83d 100%); color: #fff; overflow: hidden; }\n" +
            "  .toolbar-container { background: rgba(233, 222, 222, 0.46); padding: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.2); }\n" +
            "  .toolbar { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-bottom: 6px; }\n" +
            "  .toolbar:last-child { margin-bottom: 0; }\n" +
            "  .image-tools { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-left: 0; padding-left: 0; border-left: 0; }\n" +
            "  .toolbar button { \n" +
            "    background: #3a3a3a; \n" +
            "    color: #e0e0e0; \n" +
            "    border: 1px solid #555; \n" +
            "    padding: 10px 16px; \n" +
            "    cursor: pointer; \n" +
            "    border-radius: 6px; \n" +
            "    font-size: 14px;\n" +
            "    font-weight: bold;\n" +
            "    font-family: 'Segoe UI Emoji', sans-serif;\n" +
            "    box-shadow: 0 2px 4px rgba(0,0,0,0.2);\n" +
            "    transition: all 0.2s ease;\n" +
            "  }\n" +
            "  .toolbar button:hover { \n" +
            "    background: #4a4a4a; \n" +
            "    border-color: #667eea; \n" +
            "    box-shadow: 0 4px 8px rgba(0,0,0,0.3);\n" +
            "    transform: translateY(-1px);\n" +
            "  }\n" +
            "  .toolbar button:active { \n" +
            "    background: #2a2a2a; \n" +
            "    transform: translateY(0); \n" +
            "  }\n" +
            "  .toolbar select { \n" +
            "    background: #3a3a3a; \n" +
            "    color: #e0e0e0; \n" +
            "    border: 1px solid #555; \n" +
            "    padding: 10px 12px; \n" +
            "    border-radius: 6px; \n" +
            "    font-size: 14px;\n" +
            "    font-weight: bold;\n" +
            "    cursor: pointer;\n" +
            "    box-shadow: 0 2px 4px rgba(0,0,0,0.2);\n" +
            "  }\n" +
            "  .toolbar input[type=\"file\"] { display: none; }\n" +
            "  .toolbar label { color: #333; font-size: 12px; font-weight: 600; margin: 0 4px; }\n" +
            "  .separator { width: 1px; height: 28px; background: linear-gradient(to bottom, #667eea, #764ba2); margin: 0 6px; opacity: 0.3; }\n" +
            "  #editor { \n" +
            "    height: calc(100vh - 180px); \n" +
            "    padding: 30px; \n" +
            "    background: #ffffff; \n" +
            "    color: #333; \n" +
            "    outline: none; \n" +
            "    font-size: 14px; \n" +
            "    line-height: 1.8; \n" +
            "    overflow-y: auto;\n" +
            "    box-shadow: inset 0 2px 10px rgba(0,0,0,0.05);\n" +
            "  }\n" +
            "  #editor:focus { background: #fafafa; }\n" +
            "  #editor img { max-width: 100%; height: auto; display: block; margin: 15px 0; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }\n" +
            "  #editor img.key-image-selected { outline: 2px dashed #1976d2; outline-offset: 3px; }\n" +
            "  #editor table { border-collapse: collapse; width: 100%; margin: 15px 0; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }\n" +
            "  #editor td, #editor th { border: 1px solid #e0e0e0; padding: 10px; text-align: left; }\n" +
            "  #editor th { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; font-weight: bold; }\n" +
            "  #editor p { margin: 12px 0; }\n" +
            "  #editor ul, #editor ol { margin: 12px 0; padding-left: 35px; }\n" +
            "  #editor h1, #editor h2, #editor h3 { margin: 20px 0 15px 0; color: #667eea; }\n" +
            "  #editor h1 { font-size: 28px; border-bottom: 3px solid #667eea; padding-bottom: 8px; }\n" +
            "  #editor h2 { font-size: 24px; border-bottom: 2px solid #764ba2; padding-bottom: 6px; }\n" +
            "  #editor h3 { font-size: 20px; color: #764ba2; }\n" +
            "  #editor a { color: #667eea; text-decoration: none; }\n" +
            "  #editor blockquote { border-left: 4px solid #667eea; padding-left: 20px; margin: 15px 0; color: #666; font-style: italic; background: #f8f9ff; padding: 10px 20px; border-radius: 4px; }\n" +
            "  #editor hr { border: none; height: 2px; background: linear-gradient(to right, #667eea, #764ba2); margin: 20px 0; border-radius: 1px; }\n" +
            "  .status-bar { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 8px 15px; font-size: 11px; color: white; display: flex; justify-content: space-between; box-shadow: 0 -2px 10px rgba(0,0,0,0.1); }\n" +
            "</style>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <div class=\"toolbar-container\">\n" +
            "    <div class=\"toolbar\">\n" +
            "      <button onclick=\"execCmd('undo')\" title=\"Undo\">↶ Undo</button>\n" +
            "      <button onclick=\"execCmd('redo')\" title=\"Redo\">↷ Redo</button>\n" +
            "      <div class=\"separator\"></div>\n" +
            "      <button onclick=\"changeFontSize(-1)\" title=\"Decrease Font\">A-</button>\n" +
            "      <button onclick=\"changeFontSize(1)\" title=\"Increase Font\">A+</button>\n" +
            "      <button onmousedown=\"event.preventDefault()\" onclick=\"changeSelectedFontSize(-1)\" title=\"Decrease selected text size\">Selection A-</button>\n" +
            "      <button onmousedown=\"event.preventDefault()\" onclick=\"changeSelectedFontSize(1)\" title=\"Increase selected text size\">Selection A+</button>\n" +
            "      <div class=\"separator\"></div>\n" +
            "      <select onchange=\"execCmd('formatBlock', this.value); this.value='';\" title=\"Format\">\n" +
            "        <option value=\"\">Format</option>\n" +
            "        <option value=\"H1\">Heading 1</option>\n" +
            "        <option value=\"H2\">Heading 2</option>\n" +
            "        <option value=\"H3\">Heading 3</option>\n" +
            "        <option value=\"P\">Paragraph</option>\n" +
            "      </select>\n" +
            "      <button onclick=\"execCmd('bold')\" title=\"Bold (Ctrl+B)\"><b>B</b></button>\n" +
            "      <button onclick=\"execCmd('italic')\" title=\"Italic (Ctrl+I)\"><i>I</i></button>\n" +
            "      <button onclick=\"execCmd('underline')\" title=\"Underline (Ctrl+U)\"><u>U</u></button>\n" +
            "      <button onclick=\"execCmd('strikeThrough')\" title=\"Strikethrough\"><s>S</s></button>\n" +
            "    </div>\n" +
            "    <div class=\"toolbar\">\n" +
            "      <button onclick=\"execCmd('justifyLeft')\" title=\"Align Left\">⬅ Align Left</button>\n" +
            "      <button onclick=\"execCmd('justifyCenter')\" title=\"Align Center\">⬌ Center</button>\n" +
            "      <button onclick=\"execCmd('justifyRight')\" title=\"Align Right\">➡ Align Right</button>\n" +
            "      <button onclick=\"execCmd('justifyFull')\" title=\"Justify\">⬿ Justify</button>\n" +
            "      <div class=\"separator\"></div>\n" +
            "      <button onclick=\"execCmd('insertUnorderedList')\" title=\"Bullet List\">• Bullet List</button>\n" +
            "      <button onclick=\"execCmd('insertOrderedList')\" title=\"Numbered List\">1. Numbered List</button>\n" +
            "      <button onclick=\"execCmd('outdent')\" title=\"Decrease Indent\">⇤ Outdent</button>\n" +
            "      <button onclick=\"execCmd('indent')\" title=\"Increase Indent\">⇥ Indent</button>\n" +
            "      <div class=\"separator\"></div>\n" +
            "      <button onclick=\"insertTable()\" title=\"Insert Table\">⊞ Table</button>\n" +
            "      <button onclick=\"execCmd('insertHorizontalRule')\" title=\"Horizontal Line\">― Line</button>\n" +
            "      <div class=\"image-tools\" title=\"Key image tools\">\n" +
            "      <button onclick=\"insertImage()\" title=\"Insert an image from a file\">Insert File</button>\n" +
            "      <button onclick=\"captureKeyImage()\" title=\"Minimize windows and capture a screen region\">Capture</button>\n" +
            "      <button onclick=\"pasteKeyImage()\" title=\"Insert an image from the system clipboard\">Paste Image</button>\n" +
            "      <select onchange=\"resizeSelectedImage(this.value); this.value='';\" title=\"Set selected image width\">\n" +
            "        <option value=\"\">Image size</option>\n" +
            "        <option value=\"25%\">25%</option>\n" +
            "        <option value=\"50%\">50%</option>\n" +
            "        <option value=\"75%\">75%</option>\n" +
            "        <option value=\"100%\">100%</option>\n" +
            "      </select>\n" +
            "      <select onchange=\"alignSelectedImage(this.value); this.value='';\" title=\"Position selected image\">\n" +
            "        <option value=\"\">Image position</option>\n" +
            "        <option value=\"left\">Left</option>\n" +
            "        <option value=\"center\">Center</option>\n" +
            "        <option value=\"right\">Right</option>\n" +
            "      </select>\n" +
            "      <select onchange=\"formatSelectedImage(this.value); this.value='';\" title=\"Image border and frame\">\n" +
            "        <option value=\"\">Image format</option>\n" +
            "        <option value=\"plain\">Plain</option>\n" +
            "        <option value=\"border\">Border</option>\n" +
            "        <option value=\"frame\">Framed</option>\n" +
            "      </select>\n" +
            "      </div>\n" +
            "      <div class=\"separator\"></div>\n" +
            "      <button onclick=\"clearFormatting()\" title=\"Remove formatting from the selected text\">✕ Clear Formatting</button>\n" +
            "      <button onclick=\"clearReport()\" title=\"Remove all report content\">Clear Report</button>\n" +
            "      <button onclick=\"execCmd('selectAll')\" title=\"Select All\">☰ Select All</button>\n" +
            "    </div>\n" +
            "  </div>\n" +
            "  <div id=\"editor\" contenteditable=\"true\">\n" +
            "    <h1>Radiology Report Editor</h1>\n" +
            "    <p>Welcome to your <b>advanced report editor</b>!</p>\n" +
            "    <p><b>Features:</b></p>\n" +
            "    <ul>\n" +
            "      <li>Colorful, modern interface with gradient themes</li>\n" +
            "      <li>Full formatting (bold, italic, underline, strikethrough)</li>\n" +
            "      <li>Adjustable font size (A+ / A- buttons)</li>\n" +
            "      <li>Multiple heading levels and paragraph styles</li>\n" +
            "      <li>Text alignment options</li>\n" +
            "      <li>Bullet and numbered lists with indentation</li>\n" +
            "      <li>Capture a screen region or paste a key image from the clipboard</li>\n" +
            "      <li>Create tables with custom rows and columns</li>\n" +
            "      <li>Load .docx files (use the Load Report button)</li>\n" +
            "      <li>Paste directly from Word with formatting preserved</li>\n" +
            "      <li>Save content as HTML or DOCX</li>\n" +
            "    </ul>\n" +
            "    <p>Start creating your report now!</p>\n" +
            "  </div>\n" +
            "  <div class=\"status-bar\">\n" +
            "    <span id=\"wordCount\">Words: 0 | Characters: 0</span>\n" +
            "    <span id=\"status\">Ready</span>\n" +
            "  </div>\n" +
            "  <script>\n" +
            "    const editor = document.getElementById('editor');\n" +
            "    const wordCountEl = document.getElementById('wordCount');\n" +
            "    const statusEl = document.getElementById('status');\n" +
            "    let currentFontSize = 14;\n" +
            "    let savedEditorRange = null;\n" +
            "    let selectedImage = null;\n" +
            "    function saveEditorSelection() {\n" +
            "      const selection = window.getSelection();\n" +
            "      if (selection && selection.rangeCount > 0 && editor.contains(selection.anchorNode) && editor.contains(selection.focusNode)) {\n" +
            "        savedEditorRange = selection.getRangeAt(0).cloneRange();\n" +
            "      }\n" +
            "    }\n" +
            "    function restoreEditorSelection() {\n" +
            "      if (!savedEditorRange || !editor.contains(savedEditorRange.commonAncestorContainer)) return false;\n" +
            "      const selection = window.getSelection();\n" +
            "      selection.removeAllRanges();\n" +
            "      selection.addRange(savedEditorRange);\n" +
            "      return true;\n" +
            "    }\n" +
            "    function insertTextAtSavedSelection(text) {\n" +
            "      editor.focus();\n" +
            "      if (!restoreEditorSelection()) {\n" +
            "        const selection = window.getSelection();\n" +
            "        const range = document.createRange();\n" +
            "        range.selectNodeContents(editor);\n" +
            "        range.collapse(false);\n" +
            "        selection.removeAllRanges();\n" +
            "        selection.addRange(range);\n" +
            "      }\n" +
            "      document.execCommand('insertText', false, text);\n" +
            "      saveEditorSelection();\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    \n" +
            "    function execCmd(command, value) {\n" +
            "      restoreEditorSelection();\n" +
            "      editor.focus();\n" +
            "      document.execCommand(command, false, value);\n" +
            "      saveEditorSelection();\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    function clearFormatting() {\n" +
            "      if (!restoreEditorSelection()) { statusEl.textContent = 'Select report text before clearing its formatting.'; return; }\n" +
            "      const selection = window.getSelection();\n" +
            "      if (!selection || selection.isCollapsed) { statusEl.textContent = 'Select report text before clearing its formatting.'; return; }\n" +
            "      editor.focus();\n" +
            "      document.execCommand('removeFormat', false, null);\n" +
            "      saveEditorSelection();\n" +
            "      statusEl.textContent = 'Formatting cleared from the selected text.';\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    function clearReport() {\n" +
            "      if (!window.confirm('Clear all report text, tables, and images?')) return;\n" +
            "      editor.innerHTML = '<p><br></p>';\n" +
            "      selectedImage = null;\n" +
            "      savedEditorRange = null;\n" +
            "      statusEl.textContent = 'Report cleared.';\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    \n" +
            "    function changeFontSize(delta) {\n" +
            "      currentFontSize = Math.max(8, Math.min(36, currentFontSize + delta));\n" +
            "      editor.style.fontSize = currentFontSize + 'px';\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    function changeSelectedFontSize(delta) {\n" +
            "      if (!restoreEditorSelection()) { statusEl.textContent = 'Select text in the report first.'; return; }\n" +
            "      const selection = window.getSelection();\n" +
            "      if (!selection || selection.isCollapsed) { statusEl.textContent = 'Select text in the report first.'; return; }\n" +
            "      const range = selection.getRangeAt(0);\n" +
            "      let node = range.startContainer.nodeType === Node.ELEMENT_NODE ? range.startContainer : range.startContainer.parentElement;\n" +
            "      if (!node || !editor.contains(node)) { statusEl.textContent = 'Select text in the report first.'; return; }\n" +
            "      const currentSize = parseFloat(window.getComputedStyle(node).fontSize) || currentFontSize;\n" +
            "      const span = document.createElement('span');\n" +
            "      span.style.fontSize = Math.max(8, Math.min(72, currentSize + delta)) + 'px';\n" +
            "      span.appendChild(range.extractContents());\n" +
            "      range.insertNode(span);\n" +
            "      selection.removeAllRanges();\n" +
            "      const selectedRange = document.createRange();\n" +
            "      selectedRange.selectNodeContents(span);\n" +
            "      selection.addRange(selectedRange);\n" +
            "      saveEditorSelection();\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    \n" +
            "    function insertImage() {\n" +
            "      saveEditorSelection();\n" +
            "      const input = document.createElement('input');\n" +
            "      input.type = 'file';\n" +
            "      input.accept = 'image/*';\n" +
            "      input.onchange = function(e) {\n" +
            "        const file = e.target.files[0];\n" +
            "        if (file) {\n" +
            "          const reader = new FileReader();\n" +
            "          reader.onload = function(event) {\n" +
            "            editor.focus();\n" +
            "            if (!restoreEditorSelection()) {\n" +
            "              const selection = window.getSelection();\n" +
            "              const range = document.createRange();\n" +
            "              range.selectNodeContents(editor);\n" +
            "              range.collapse(false);\n" +
            "              selection.removeAllRanges();\n" +
            "              selection.addRange(range);\n" +
            "            }\n" +
            "            const selection = window.getSelection();\n" +
            "            const range = selection.getRangeAt(0);\n" +
            "            range.deleteContents();\n" +
            "            const image = document.createElement('img');\n" +
            "            image.src = event.target.result;\n" +
            "            image.alt = file.name;\n" +
            "            image.style.width = '50%';\n" +
            "            image.style.height = 'auto';\n" +
            "            image.style.display = 'block';\n" +
            "            image.style.margin = '15px 0';\n" +
            "            range.insertNode(image);\n" +
            "            range.setStartAfter(image);\n" +
            "            range.collapse(true);\n" +
            "            selection.removeAllRanges();\n" +
            "            selection.addRange(range);\n" +
            "            if (selectedImage) selectedImage.classList.remove('key-image-selected');\n" +
            "            selectedImage = image;\n" +
            "            selectedImage.classList.add('key-image-selected');\n" +
            "            saveEditorSelection();\n" +
            "            updateStatus();\n" +
            "          };\n" +
            "          reader.readAsDataURL(file);\n" +
            "        }\n" +
            "      };\n" +
            "      input.click();\n" +
            "    }\n" +
            "    function insertImageDataUrl(dataUrl, altText) {\n" +
            "      if (!dataUrl || dataUrl.indexOf('data:image/') !== 0) { statusEl.textContent = 'No image data available.'; return; }\n" +
            "      editor.focus();\n" +
            "      if (!restoreEditorSelection()) {\n" +
            "        const selection = window.getSelection();\n" +
            "        const range = document.createRange();\n" +
            "        range.selectNodeContents(editor);\n" +
            "        range.collapse(false);\n" +
            "        selection.removeAllRanges();\n" +
            "        selection.addRange(range);\n" +
            "      }\n" +
            "      const selection = window.getSelection();\n" +
            "      const range = selection.getRangeAt(0);\n" +
            "      range.deleteContents();\n" +
            "      const image = document.createElement('img');\n" +
            "      image.src = dataUrl;\n" +
            "      image.alt = altText || 'Key image';\n" +
            "      image.style.width = '50%';\n" +
            "      image.style.height = 'auto';\n" +
            "      image.style.display = 'block';\n" +
            "      image.style.margin = '15px 0';\n" +
            "      range.insertNode(image);\n" +
            "      range.setStartAfter(image);\n" +
            "      range.collapse(true);\n" +
            "      selection.removeAllRanges();\n" +
            "      selection.addRange(range);\n" +
            "      if (selectedImage) selectedImage.classList.remove('key-image-selected');\n" +
            "      selectedImage = image;\n" +
            "      selectedImage.classList.add('key-image-selected');\n" +
            "      saveEditorSelection();\n" +
            "      statusEl.textContent = 'Key image inserted. Select it to adjust size, position, and format.';\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    function captureKeyImage() {\n" +
            "      saveEditorSelection();\n" +
            "      window.javaBridge.captureKeyImage();\n" +
            "    }\n" +
            "    function pasteKeyImage() {\n" +
            "      saveEditorSelection();\n" +
            "      window.javaBridge.pasteKeyImage();\n" +
            "    }\n" +
            "    function resizeSelectedImage(width) {\n" +
            "      if (!width) return;\n" +
            "      if (!selectedImage || !editor.contains(selectedImage)) { statusEl.textContent = 'Select an image in the report first.'; return; }\n" +
            "      selectedImage.style.width = width;\n" +
            "      selectedImage.style.height = 'auto';\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    function alignSelectedImage(alignment) {\n" +
            "      if (!alignment) return;\n" +
            "      if (!selectedImage || !editor.contains(selectedImage)) { statusEl.textContent = 'Select an image in the report first.'; return; }\n" +
            "      selectedImage.style.display = 'block';\n" +
            "      selectedImage.style.marginLeft = alignment === 'right' ? 'auto' : '0';\n" +
            "      selectedImage.style.marginRight = alignment === 'left' ? 'auto' : (alignment === 'center' ? 'auto' : '0');\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    function formatSelectedImage(format) {\n" +
            "      if (!format) return;\n" +
            "      if (!selectedImage || !editor.contains(selectedImage)) { statusEl.textContent = 'Select an image in the report first.'; return; }\n" +
            "      selectedImage.dataset.imageFormat = format;\n" +
            "      selectedImage.style.border = format === 'plain' ? 'none' : (format === 'frame' ? '8px solid #ffffff' : '1px solid #777');\n" +
            "      selectedImage.style.padding = format === 'frame' ? '4px' : '0';\n" +
            "      selectedImage.style.backgroundColor = format === 'frame' ? '#ffffff' : 'transparent';\n" +
            "      selectedImage.style.boxShadow = format === 'frame' ? '0 2px 10px rgba(0,0,0,0.25)' : 'none';\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    \n" +
            "    function insertTable() {\n" +
            "      const rows = prompt('Number of rows:', '3');\n" +
            "      const cols = prompt('Number of columns:', '3');\n" +
            "      if (rows && cols) {\n" +
            "        let html = '<table border=\"1\" style=\"border-collapse: collapse; width: 100%;\">';\n" +
            "        for (let i = 0; i < parseInt(rows); i++) {\n" +
            "          html += '<tr>';\n" +
            "          for (let j = 0; j < parseInt(cols); j++) {\n" +
            "            html += '<td style=\"padding: 10px; border: 1px solid #e0e0e0;\">&nbsp;</td>';\n" +
            "          }\n" +
            "          html += '</tr>';\n" +
            "        }\n" +
            "        html += '</table><p><br></p>';\n" +
            "        execCmd('insertHTML', html);\n" +
            "      }\n" +
            "    }\n" +
            "    \n" +
            "    function updateStatus() {\n" +
            "      const text = editor.innerText || editor.textContent;\n" +
            "      const words = text.trim().split(/\\s+/).filter(w => w.length > 0).length;\n" +
            "      const chars = text.length;\n" +
            "      wordCountEl.textContent = 'Words: ' + words + ' | Characters: ' + chars + ' | Font: ' + currentFontSize + 'px';\n" +
            "    }\n" +
            "    \n" +
            "    editor.addEventListener('input', updateStatus);\n" +
            "    editor.addEventListener('mouseup', saveEditorSelection);\n" +
            "    editor.addEventListener('keyup', saveEditorSelection);\n" +
            "    editor.addEventListener('click', function(event) {\n" +
            "      const image = event.target.closest && event.target.closest('img');\n" +
            "      if (selectedImage) selectedImage.classList.remove('key-image-selected');\n" +
            "      selectedImage = image && editor.contains(image) ? image : null;\n" +
            "      if (selectedImage) selectedImage.classList.add('key-image-selected');\n" +
            "      if (selectedImage) statusEl.textContent = 'Image selected. Use image controls to adjust it.';\n" +
            "    });\n" +
            "    document.addEventListener('selectionchange', saveEditorSelection);\n" +
            "    document.querySelectorAll('.toolbar button').forEach(function(control) {\n" +
            "      control.addEventListener('mousedown', function(event) { event.preventDefault(); });\n" +
            "    });\n" +
            "    document.querySelectorAll('.toolbar select').forEach(function(control) {\n" +
            "      control.addEventListener('mousedown', saveEditorSelection);\n" +
            "    });\n" +
            "    editor.addEventListener('paste', function(e) {\n" +
            "      statusEl.textContent = 'Pasting...';\n" +
            "      const items = e.clipboardData && e.clipboardData.items;\n" +
            "      if (items) {\n" +
            "        for (let i = 0; i < items.length; i++) {\n" +
            "          if (items[i].type && items[i].type.indexOf('image/') === 0) {\n" +
            "            const file = items[i].getAsFile();\n" +
            "            if (file) {\n" +
            "              e.preventDefault();\n" +
            "              const reader = new FileReader();\n" +
            "              reader.onload = function(event) { insertImageDataUrl(event.target.result, 'Pasted key image'); };\n" +
            "              reader.readAsDataURL(file);\n" +
            "              return;\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "      setTimeout(updateStatus, 100);\n" +
            "    });\n" +
            "    \n" +
            "    editor.addEventListener('keydown', function(e) {\n" +
            "      if (e.ctrlKey || e.metaKey) {\n" +
            "        switch(e.key.toLowerCase()) {\n" +
            "          case 'b': e.preventDefault(); execCmd('bold'); break;\n" +
            "          case 'i': e.preventDefault(); execCmd('italic'); break;\n" +
            "          case 'u': e.preventDefault(); execCmd('underline'); break;\n" +
            "        }\n" +
            "      }\n" +
            "    });\n" +
            "    \n" +
            "    function setContent(html) {\n" +
            "      editor.innerHTML = html;\n" +
            "      updateStatus();\n" +
            "    }\n" +
            "    \n" +
            "    function getContent() {\n" +
            "      const copy = editor.cloneNode(true);\n" +
            "      copy.querySelectorAll('.key-image-selected').forEach(function(image) { image.classList.remove('key-image-selected'); });\n" +
            "      return copy.innerHTML;\n" +
            "    }\n" +
            "    \n" +
            "    updateStatus();\n" +
            "  </script>\n" +
            "</body>\n" +
            "</html>";
        
        EditorImageBridge editorImageBridge = new EditorImageBridge(this, ownerStage, aiStage, editorEngine);
        editorEngine.loadContent(editorHtml);
        
        final File[] currentFile = new File[1];
        editorEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                netscape.javascript.JSObject window = (netscape.javascript.JSObject) editorEngine.executeScript("window");
                window.setMember("javaBridge", editorImageBridge);
                
                // If a specific file was requested, load it
                if (fileToOpen != null && fileToOpen.exists()) {
                    statusLabel.setText("LOADING REPORT...");
                    new Thread(() -> {
                        try {
                            String fileName = fileToOpen.getName().toLowerCase();
                            if (fileName.endsWith(".docx") || fileName.endsWith(".doc")) {
                                String html = convertDocxToHtml(fileToOpen);
                                final String htmlContent = html;
                                Platform.runLater(() -> {
                                    try {
                                        String escaped = htmlContent.replace("\\", "\\\\")
                                                                   .replace("'", "\\'")
                                                                   .replace("\n", "\\n")
                                                                   .replace("\r", "")
                                                                   .replace("\t", "\\t");
                                        editorEngine.executeScript("document.getElementById('editor').innerHTML = '" + escaped + "';");
                                        currentFile[0] = fileToOpen;
                                        saveLastReportPath(fileToOpen);
                                        statusLabel.setText("LOADED: " + fileToOpen.getName());
                                    } catch (Exception ex) {
                                        statusLabel.setText("Error loading report. Please try again.");
                                    }
                                });
                            } else {
                                String content = new String(java.nio.file.Files.readAllBytes(fileToOpen.toPath()), "UTF-8");
                                final String fileContent = content;
                                Platform.runLater(() -> {
                                    try {
                                        String escaped = fileContent.replace("\\", "\\\\")
                                                                    .replace("'", "\\'")
                                                                    .replace("\n", "\\n")
                                                                    .replace("\r", "")
                                                                    .replace("\t", "\\t");
                                        editorEngine.executeScript("document.getElementById('editor').innerHTML = '" + escaped + "';");
                                        currentFile[0] = fileToOpen;
                                        saveLastReportPath(fileToOpen);
                                        statusLabel.setText("LOADED: " + fileToOpen.getName());
                                    } catch (Exception ex) {
                                        statusLabel.setText("Error loading report. Please try again.");
                                    }
                                });
                            }
                        } catch (Exception ex) {
                            Platform.runLater(() -> {
                                statusLabel.setText("Could not load report: " + ex.getMessage());
                            });
                        }
                    }).start();
                } else {
                    // Load the last edited report if available
                    File lastReport = loadLastReportPath();
                    if (lastReport != null && lastReport.exists()) {
                        try {
                            String fileName = lastReport.getName().toLowerCase();
                            if (fileName.endsWith(".docx") || fileName.endsWith(".doc")) {
                                statusLabel.setText("LOADING LAST REPORT...");
                                new Thread(() -> {
                                    try {
                                        String html = convertDocxToHtml(lastReport);
                                        final String htmlContent = html;
                                        Platform.runLater(() -> {
                                            try {
                                                String escaped = htmlContent.replace("\\", "\\\\")
                                                                           .replace("'", "\\'")
                                                                           .replace("\n", "\\n")
                                                                           .replace("\r", "")
                                                                           .replace("\t", "\\t");
                                                editorEngine.executeScript("document.getElementById('editor').innerHTML = '" + escaped + "';");
                                                currentFile[0] = lastReport;
                                                statusLabel.setText("LOADED LAST REPORT: " + lastReport.getName());
                                            } catch (Exception ex) {
                                                statusLabel.setText("Could not load last report: " + ex.getMessage());
                                            }
                                        });
                                    } catch (Exception ex) {
                                        Platform.runLater(() -> {
                                            statusLabel.setText("Could not load last report: " + ex.getMessage());
                                        });
                                    }
                                }).start();
                            } else {
                                String content = new String(java.nio.file.Files.readAllBytes(lastReport.toPath()), "UTF-8");
                                final String fileContent = content;
                                Platform.runLater(() -> {
                                    try {
                                        String escaped = fileContent.replace("\\", "\\\\")
                                                                    .replace("'", "\\'")
                                                                    .replace("\n", "\\n")
                                                                    .replace("\r", "")
                                                                    .replace("\t", "\\t");
                                        editorEngine.executeScript("document.getElementById('editor').innerHTML = '" + escaped + "';");
                                        currentFile[0] = lastReport;
                                        statusLabel.setText("LOADED LAST REPORT: " + lastReport.getName());
                                    } catch (Exception ex) {
                                        statusLabel.setText("Could not load last report: " + ex.getMessage());
                                    }
                                });
                            }
                        } catch (Exception ex) {
                            Platform.runLater(() -> {
                                statusLabel.setText("Could not load last report: " + ex.getMessage());
                            });
                        }
                    }
                }
            }
        });
        
        Label reportLabel = new Label("📝 Report Editor");
        reportLabel.setStyle("-fx-text-fill: #e0e0e0; -fx-font-weight: bold; -fx-font-size: 14px;");
        
        Button btnLoadReport = new Button("📂 Load Report");
        btnLoadReport.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-border-radius:6px;-fx-font-weight: bold; -fx-background-color: #3a3a3a; -fx-text-fill: #e0e0e0; -fx-border-color: #555; -fx-border-width: 1; -fx-padding: 10 16 10 16;");
        btnLoadReport.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Load Report File");
            fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Word Documents", "*.docx", "*.doc"),
                new FileChooser.ExtensionFilter("HTML Files", "*.html", "*.htm"),
                new FileChooser.ExtensionFilter("Text Files", "*.txt"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            if (outputDirectory != null) fc.setInitialDirectory(outputDirectory);
            File selected = fc.showOpenDialog(aiStage);
            if (selected == null) return;
            
            try {
                String fileName = selected.getName().toLowerCase();
                
                if (fileName.endsWith(".docx") || fileName.endsWith(".doc") || fileName.endsWith(".DOCX") || fileName.endsWith(".DOC")) {
                    if (!selected.exists()) {
                        showError("File Not Found", "The selected file does not exist:\n" + selected.getAbsolutePath());
                        return;
                    }
                    
                    statusLabel.setText("CONVERTING DOCX...");
                    new Thread(() -> {
                        try {
                            String html = convertDocxToHtml(selected);
                            final String htmlContent = html;
                                Platform.runLater(() -> {
                                    String escaped = htmlContent.replace("\\", "\\\\")
                                                               .replace("'", "\\'")
                                                               .replace("\n", "\\n")
                                                               .replace("\r", "")
                                                               .replace("\t", "\\t");
                                    editorEngine.executeScript("document.getElementById('editor').innerHTML = '" + escaped + "';");
                                    lastGeneratedWordFile = selected;
                                    statusLabel.setText("LOADED: " + selected.getName());
                                });
                        } catch (Exception ex) {
                            Platform.runLater(() -> {
                                showError("Conversion Error", "Could not convert DOCX: " + ex.getMessage());
                                statusLabel.setText("CONVERSION FAILED");
                            });
                        }
                    }).start();
                    return;
                }
                
                String content = new String(java.nio.file.Files.readAllBytes(selected.toPath()), "UTF-8");
                content = content.replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\n", "\\n")
                                .replace("\r", "")
                                .replace("\t", "\\t");
                editorEngine.executeScript("document.getElementById('editor').innerHTML = '" + content + "';");
                lastGeneratedWordFile = selected;
                statusLabel.setText("LOADED: " + selected.getName());
                
            } catch (Exception ex) {
                showError("Load Error", "Could not load file:\n" + ex.getMessage());
                ex.printStackTrace();
            }
        });
        
        Button btnSaveReport = new Button("💾 Save HTML");
        btnSaveReport.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: #3a3a3a;-fx-border-radius: 6px; -fx-text-fill: #e0e0e0; -fx-border-color: #555; -fx-border-width: 1; -fx-padding: 10 16 10 16;");
        btnSaveReport.setOnAction(e -> {
            if (outputDirectory == null) {
                showError("Save Error", "Please set output directory first.");
                return;
            }
            try {
                String htmlContent = (String) editorEngine.executeScript("getContent();");
                String name = getReportFileBaseName();
                File reportFile = new File(outputDirectory, name + ".html");
                java.nio.file.Files.write(reportFile.toPath(), htmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                lastGeneratedWordFile = reportFile;
                saveLastReportPath(reportFile);
                statusLabel.setText("REPORT SAVED: " + reportFile.getName());
                showInfo("Success", "Report saved as HTML!");
            } catch (Exception ex) {
                showError("Save Error", "Could not save file: " + ex.getMessage());
            }
        });
        
        Button btnSaveAsDocx = new Button("📄 Save as DOCX");
        btnSaveAsDocx.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: #3a3a3a; -fx-text-fill: #e0e0e0; -fx-border-color: #555; -fx-border-width: 1; -fx-padding: 10 16 10 16; -fx-border-radius: 6px;");
        btnSaveAsDocx.setOnAction(e -> {
            if (outputDirectory == null) {
                showError("Save Error", "Please set output directory first.");
                return;
            }
            try {
                String htmlContent = (String) editorEngine.executeScript("getContent();");
                String name = getReportFileBaseName();
                File docxFile = new File(outputDirectory, name + ".docx");
                saveHtmlAsDocx(htmlContent, docxFile);
            } catch (Exception ex) {
                showError("Save Error", "Could not save DOCX: " + ex.getMessage());
            }
        });

        Button btnOpenChatGpt = new Button("🌐 Open ChatGPT");
        btnOpenChatGpt.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: #18a957; -fx-text-fill: white; -fx-border-color: #555; -fx-border-width: 1; -fx-padding: 10 16 10 16; -fx-border-radius: 6px;");
        btnOpenChatGpt.setOnAction(e -> openChatGptSecurely());
        
        Button btnCopyToClipboard = new Button("📋 Copy");
        btnCopyToClipboard.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: #3a3a3a;-fx-border-radius: 6px; -fx-text-fill: #e0e0e0; -fx-border-color: #555; -fx-border-width: 1; -fx-padding: 10 16 10 16;");
        btnCopyToClipboard.setOnAction(e -> {
            try {
                String selectedText = (String) editorEngine.executeScript("window.getSelection().toString();");
                ClipboardContent content = new ClipboardContent();
                content.putString(selectedText);
                Clipboard.getSystemClipboard().setContent(content);
                statusLabel.setText("COPIED TO CLIPBOARD");
            } catch (Exception ex) {
                showError("Copy Error", "Could not copy text");
            }
        });
        
        Button btnPasteFromClipboard = new Button("📄 Paste");
        btnPasteFromClipboard.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: #3a3a3a; -fx-text-fill: #e0e0e0; -fx-border-color: #555; -fx-border-radius: 6px; -fx-border-width: 1; -fx-padding: 10 16 10 16;");
        btnPasteFromClipboard.setOnAction(e -> {
            String clipboard = Clipboard.getSystemClipboard().getString();
            if (clipboard != null) {
                editorEngine.executeScript("document.execCommand('insertText', false, '" + clipboard.replace("'", "\\'") + "');");
                statusLabel.setText("PASTED FROM CLIPBOARD");
            }
        });
        
        HBox reportActions = new HBox(10, btnLoadReport, btnSaveReport, btnSaveAsDocx, btnOpenChatGpt, btnCopyToClipboard, btnPasteFromClipboard);
        reportActions.setStyle("-fx-background-color: #2a2a2a; -fx-border-color: #444; -fx-padding: 5;");
        
        VBox reportPanel = new VBox(5, reportLabel, reportActions, editorWebView);
        reportPanel.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #444; -fx-padding: 5;");
        VBox.setVgrow(editorWebView, Priority.ALWAYS);

        ObservableList<ReportShortcut> shortcutData = FXCollections.observableArrayList();
        boolean shortcutsLoaded;
        try {
            shortcutData.setAll(loadReportingShortcuts());
            shortcutsLoaded = true;
        } catch (Exception ex) {
            shortcutsLoaded = false;
            showError("Shortcuts Load Error",
                    "Could not load reporting shortcuts. The shortcut file was left unchanged:\n"
                            + getReportingShortcutsPath() + "\n\n" + ex.getMessage());
        }
        final boolean canEditShortcuts = shortcutsLoaded;

        Label shortcutsTitle = new Label("Reporting Shortcuts");
        shortcutsTitle.setStyle("-fx-text-fill: #e0e0e0; -fx-font-weight: bold; -fx-font-size: 16px;");
        Label shortcutsHint = new Label("Double-click a phrase or select it and press Insert.");
        shortcutsHint.setWrapText(true);
        ListView<ReportShortcut> shortcutList = new ListView<>(shortcutData);
        shortcutList.setPlaceholder(new Label("No shortcuts yet. Add a phrase to get started."));
        shortcutList.setCellFactory(list -> new ListCell<ReportShortcut>() {
            @Override
            protected void updateItem(ReportShortcut shortcut, boolean empty) {
                super.updateItem(shortcut, empty);
                if (empty || shortcut == null) {
                    setText(null);
                } else {
                    String preview = shortcut.getPhrase().replaceAll("\\s+", " ").trim();
                    if (preview.length() > 90) preview = preview.substring(0, 87) + "...";
                    setText("[" + shortcut.getSection() + " | " + shortcut.getModality() + "] "
                            + shortcut.getTitle() + "\n" + preview);
                }
                setWrapText(true);
            }
        });

        Button addShortcut = new Button("＋ Add");
        Button editShortcut = new Button("✏ Edit");
        Button deleteShortcut = new Button("🗑 Delete");
        Button insertShortcut = new Button("Insert");
        addShortcut.setDisable(!canEditShortcuts);
        editShortcut.setDisable(true);
        deleteShortcut.setDisable(true);
        insertShortcut.setDisable(true);

        shortcutList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            boolean disabled = selected == null || !canEditShortcuts;
            editShortcut.setDisable(disabled);
            deleteShortcut.setDisable(disabled);
            insertShortcut.setDisable(disabled);
        });
        addShortcut.setOnAction(e -> {
            ReportShortcut created = promptForReportingShortcut(aiStage, null);
            if (created == null) return;
            List<ReportShortcut> updated = new ArrayList<>(shortcutData);
            updated.add(created);
            try {
                persistReportingShortcuts(updated, shortcutData);
                shortcutList.getSelectionModel().select(created);
            } catch (Exception ex) {
                showError("Shortcut Save Error", "Could not save shortcut file:\n" + ex.getMessage());
            }
        });
        editShortcut.setOnAction(e -> {
            int selectedIndex = shortcutList.getSelectionModel().getSelectedIndex();
            if (selectedIndex < 0) return;
            ReportShortcut edited = promptForReportingShortcut(aiStage, shortcutData.get(selectedIndex));
            if (edited == null) return;
            List<ReportShortcut> updated = new ArrayList<>(shortcutData);
            updated.set(selectedIndex, edited);
            try {
                persistReportingShortcuts(updated, shortcutData);
                shortcutList.getSelectionModel().select(selectedIndex);
            } catch (Exception ex) {
                showError("Shortcut Save Error", "Could not save shortcut file:\n" + ex.getMessage());
            }
        });
        deleteShortcut.setOnAction(e -> {
            int selectedIndex = shortcutList.getSelectionModel().getSelectedIndex();
            if (selectedIndex < 0) return;
            ReportShortcut selected = shortcutData.get(selectedIndex);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete the shortcut \"" + selected.getTitle() + "\"?",
                    ButtonType.YES, ButtonType.NO);
            applyDialogTheme(confirm);
            confirm.initOwner(aiStage);
            confirm.showAndWait().ifPresent(result -> {
                if (result != ButtonType.YES) return;
                List<ReportShortcut> updated = new ArrayList<>(shortcutData);
                updated.remove(selectedIndex);
                try {
                    persistReportingShortcuts(updated, shortcutData);
                } catch (Exception ex) {
                    showError("Shortcut Delete Error", "Could not update shortcut file:\n" + ex.getMessage());
                }
            });
        });
        insertShortcut.setOnAction(e -> insertReportingShortcut(
                editorEngine, shortcutList.getSelectionModel().getSelectedItem()));
        shortcutList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                insertReportingShortcut(editorEngine, shortcutList.getSelectionModel().getSelectedItem());
            }
        });

        FlowPane shortcutActions = new FlowPane(6, 6, addShortcut, editShortcut, deleteShortcut, insertShortcut);
        Label shortcutFile = new Label("File: " + getReportingShortcutsPath());
        shortcutFile.setWrapText(true);
        shortcutFile.setStyle("-fx-text-fill: #999; -fx-font-size: 10px;");
        VBox shortcutsPanel = new VBox(8, shortcutsTitle, shortcutsHint, shortcutActions, shortcutList, shortcutFile);
        shortcutsPanel.setPadding(new Insets(10));
        shortcutsPanel.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #444;");
        shortcutsPanel.setMinWidth(260);
        shortcutsPanel.setPrefWidth(330);
        VBox.setVgrow(shortcutList, Priority.ALWAYS);

        ObservableList<ReportTemplate> templateData = FXCollections.observableArrayList();
        try {
            templateData.setAll(loadReportTemplates());
        } catch (SQLException ex) {
            showError("Template Load Error", "Could not load report templates from the database:\n" + ex.getMessage());
        }
        final String allBodyParts = "All body parts";
        final String allModalities = "All modalities";
        ComboBox<String> bodyPartFilter = new ComboBox<>();
        bodyPartFilter.setPromptText("Body part");
        bodyPartFilter.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> modalityFilter = new ComboBox<>();
        modalityFilter.setPromptText("Modality");
        modalityFilter.setMaxWidth(Double.MAX_VALUE);
        TextField templateSearch = new TextField();
        templateSearch.setPromptText("Search body part, modality, or title...");
        FilteredList<ReportTemplate> filteredTemplates = new FilteredList<>(templateData, template -> true);
        Runnable updateTemplateFilter = () -> {
            String[] terms = templateSearch.getText().trim().toLowerCase(java.util.Locale.ROOT).split("\\s+");
            String selectedBodyPart = bodyPartFilter.getValue();
            String selectedModality = modalityFilter.getValue();
            filteredTemplates.setPredicate(template -> {
                if (selectedBodyPart != null && !allBodyParts.equals(selectedBodyPart)
                        && !selectedBodyPart.equalsIgnoreCase(template.getSection())) return false;
                if (selectedModality != null && !allModalities.equals(selectedModality)
                        && !selectedModality.equalsIgnoreCase(template.getModality())) return false;
                String searchable = (template.getSection() + " " + template.getModality() + " "
                        + template.getTitle() + " " + template.getContentHtml()).toLowerCase(java.util.Locale.ROOT);
                for (String term : terms) {
                    if (!term.isEmpty() && !searchable.contains(term)) return false;
                }
                return true;
            });
        };
        Runnable refreshTemplateFilterOptions = () -> {
            String previousBodyPart = bodyPartFilter.getValue();
            String previousModality = modalityFilter.getValue();
            List<String> bodyParts = templateData.stream()
                    .map(ReportTemplate::getSection)
                    .filter(value -> value != null && !value.trim().isEmpty())
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(java.util.stream.Collectors.toList());
            List<String> modalities = templateData.stream()
                    .map(ReportTemplate::getModality)
                    .filter(value -> value != null && !value.trim().isEmpty())
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(java.util.stream.Collectors.toList());
            bodyPartFilter.getItems().setAll(allBodyParts);
            bodyPartFilter.getItems().addAll(bodyParts);
            modalityFilter.getItems().setAll(allModalities);
            modalityFilter.getItems().addAll(modalities);
            bodyPartFilter.setValue(bodyParts.contains(previousBodyPart) ? previousBodyPart : allBodyParts);
            modalityFilter.setValue(modalities.contains(previousModality) ? previousModality : allModalities);
        };
        refreshTemplateFilterOptions.run();
        bodyPartFilter.setValue(allBodyParts);
        modalityFilter.setValue(allModalities);
        ListView<ReportTemplate> templateList = new ListView<>(filteredTemplates);
        bodyPartFilter.valueProperty().addListener((obs, oldValue, newValue) -> {
            updateTemplateFilter.run();
            templateList.getSelectionModel().clearSelection();
            if (!filteredTemplates.isEmpty()) templateList.getSelectionModel().select(0);
        });
        modalityFilter.valueProperty().addListener((obs, oldValue, newValue) -> {
            updateTemplateFilter.run();
            templateList.getSelectionModel().clearSelection();
            if (!filteredTemplates.isEmpty()) templateList.getSelectionModel().select(0);
        });
        templateSearch.textProperty().addListener((obs, oldValue, newValue) -> {
            updateTemplateFilter.run();
            templateList.getSelectionModel().clearSelection();
            if (!filteredTemplates.isEmpty()) templateList.getSelectionModel().select(0);
        });
        updateTemplateFilter.run();
        Label noTemplatesLabel = new Label("No templates saved yet.");
        noTemplatesLabel.setStyle("-fx-text-fill: white;");
        templateList.setPlaceholder(noTemplatesLabel);
        templateList.setCellFactory(list -> new ListCell<ReportTemplate>() {
            @Override
            protected void updateItem(ReportTemplate template, boolean empty) {
                super.updateItem(template, empty);
                setText(empty || template == null ? null
                        : template.getSection() + " | " + template.getModality() + "\n" + template.getTitle());
            }
        });

        Button saveTemplate = new Button("Save Current as Template");
        Button updateTemplate = new Button("Update Selected from Editor");
        Button loadTemplate = new Button("Load Selected");
        Button deleteTemplate = new Button("Delete Selected");
        Button importTemplates = new Button("Import from Database");
        Label templateCount = new Label();
        templateCount.setStyle("-fx-text-fill: white;");
        Runnable updateTemplateCount = () -> templateCount.setText(
                filteredTemplates.size() + " of " + templateData.size() + " templates");
        filteredTemplates.addListener((javafx.collections.ListChangeListener<ReportTemplate>) change -> updateTemplateCount.run());
        updateTemplateCount.run();
        updateTemplate.setDisable(true);
        loadTemplate.setDisable(true);
        deleteTemplate.setDisable(true);
        templateList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            boolean disabled = selected == null;
            updateTemplate.setDisable(disabled);
            loadTemplate.setDisable(disabled);
            deleteTemplate.setDisable(disabled);
        });

        saveTemplate.setOnAction(e -> {
            String content = (String) editorEngine.executeScript("getContent();");
            ReportTemplate template = promptForReportTemplate(aiStage, null, content);
            if (template == null) return;
            try {
                templateData.add(saveReportTemplate(template));
                templateData.sort(java.util.Comparator.comparing(ReportTemplate::getSection, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ReportTemplate::getModality, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ReportTemplate::getTitle, String.CASE_INSENSITIVE_ORDER));
                refreshTemplateFilterOptions.run();
                updateTemplateFilter.run();
                statusLabel.setText("TEMPLATE SAVED: " + template.getTitle());
            } catch (SQLException ex) {
                showError("Template Save Error", "Could not save template to the database:\n" + ex.getMessage());
            }
        });
        importTemplates.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select Database Containing Report Templates");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "SQLite Databases", "*.db", "*.sqlite", "*.sqlite3"));
            File sourceFile = chooser.showOpenDialog(aiStage);
            if (sourceFile == null) return;

            importTemplates.setDisable(true);
            ioExecutor.submit(() -> {
                try {
                    TemplateImportSummary summary = importReportTemplates(sourceFile);
                    List<ReportTemplate> refreshedTemplates = loadReportTemplates();
                    Platform.runLater(() -> {
                        templateData.setAll(refreshedTemplates);
                        refreshTemplateFilterOptions.run();
                        updateTemplateFilter.run();
                        importTemplates.setDisable(false);
                        statusLabel.setText("TEMPLATES IMPORTED: " + summary.imported
                                + " added, " + summary.skipped + " existing matches skipped");
                        showInfo("Template Import Complete",
                                "Imported " + summary.imported + " template(s).\n"
                                        + "Skipped " + summary.skipped
                                        + " template(s) that already exist with the same section, modality, and title.");
                    });
                } catch (SQLException ex) {
                    Platform.runLater(() -> {
                        importTemplates.setDisable(false);
                        showError("Template Import Error",
                                "Could not import templates from the selected database:\n" + ex.getMessage());
                    });
                }
            });
        });
        updateTemplate.setOnAction(e -> {
            ReportTemplate selected = templateList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            String content = (String) editorEngine.executeScript("getContent();");
            ReportTemplate edited = promptForReportTemplate(aiStage, selected, content);
            if (edited == null) return;
            try {
                ReportTemplate saved = saveReportTemplate(edited);
                int index = templateData.indexOf(selected);
                if (index >= 0) templateData.set(index, saved);
                templateData.sort(java.util.Comparator.comparing(ReportTemplate::getSection, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ReportTemplate::getModality, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ReportTemplate::getTitle, String.CASE_INSENSITIVE_ORDER));
                refreshTemplateFilterOptions.run();
                updateTemplateFilter.run();
                statusLabel.setText("TEMPLATE UPDATED: " + saved.getTitle());
            } catch (SQLException ex) {
                showError("Template Update Error", "Could not update the selected template:\n" + ex.getMessage());
            }
        });
        loadTemplate.setOnAction(e -> {
            ReportTemplate selected = templateList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            editorEngine.executeScript("document.getElementById('editor').innerHTML = "
                    + JSONObject.quote(selected.getContentHtml()) + ";");
            statusLabel.setText("TEMPLATE LOADED: " + selected.getTitle());
        });
        templateSearch.setOnAction(e -> {
            if (templateList.getSelectionModel().getSelectedItem() == null && !filteredTemplates.isEmpty()) {
                templateList.getSelectionModel().select(0);
            }
            if (templateList.getSelectionModel().getSelectedItem() != null) loadTemplate.fire();
        });
        templateList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) loadTemplate.fire();
        });
        deleteTemplate.setOnAction(e -> {
            ReportTemplate selected = templateList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete template \"" + selected.getTitle() + "\"?",
                    ButtonType.YES, ButtonType.NO);
            applyDialogTheme(confirm);
            confirm.initOwner(aiStage);
            confirm.showAndWait().ifPresent(result -> {
                if (result != ButtonType.YES) return;
                try {
                    deleteReportTemplate(selected.getId());
                    templateData.remove(selected);
                    refreshTemplateFilterOptions.run();
                    updateTemplateFilter.run();
                    statusLabel.setText("TEMPLATE DELETED: " + selected.getTitle());
                } catch (SQLException ex) {
                    showError("Template Delete Error", "Could not delete the selected template:\n" + ex.getMessage());
                }
            });
        });

        FlowPane templateActions = new FlowPane(6, 6, saveTemplate, importTemplates,
                updateTemplate, loadTemplate, deleteTemplate);
        Label templatesHeading = new Label("Report Templates");
        templatesHeading.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px;");
        Label templateHint = new Label("Type to search all template fields; press Enter to load the selected match.");
        templateHint.setWrapText(true);
        templateHint.setStyle("-fx-text-fill: white;");
        HBox templateFilters = new HBox(6, bodyPartFilter, modalityFilter);
        HBox.setHgrow(bodyPartFilter, Priority.ALWAYS);
        HBox.setHgrow(modalityFilter, Priority.ALWAYS);
        VBox templatesPanel = new VBox(8, templatesHeading, templateFilters, templateSearch,
                templateCount, templateHint, templateActions, templateList);
        templatesPanel.setPadding(new Insets(10));
        templatesPanel.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #444;");
        VBox.setVgrow(templateList, Priority.ALWAYS);

        Tab shortcutsTab = new Tab("Shortcuts", shortcutsPanel);
        shortcutsTab.setClosable(false);
        Tab templatesTab = new Tab("Templates", templatesPanel);
        templatesTab.setClosable(false);
        ObservableList<ReportSignature> signatureData = FXCollections.observableArrayList();
        try {
            signatureData.setAll(loadReportSignatures());
        } catch (SQLException ex) {
            showError("Signature Load Error", "Could not load saved signatures from the database:\n" + ex.getMessage());
        }
        Label signaturesHeading = new Label("Saved Signatures");
        signaturesHeading.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px;");
        Label signaturesHint = new Label("Select and insert a saved signature at the report cursor.");
        signaturesHint.setWrapText(true);
        signaturesHint.setStyle("-fx-text-fill: white;");
        ListView<ReportSignature> signatureList = new ListView<>(signatureData);
        signatureList.setPlaceholder(new Label("No saved signatures yet."));
        signatureList.setCellFactory(list -> new ListCell<ReportSignature>() {
            @Override
            protected void updateItem(ReportSignature signature, boolean empty) {
                super.updateItem(signature, empty);
                setText(empty || signature == null ? null
                        : signature.getTitle() + "\n" + signature.getText().replaceAll("\\s+", " ").trim());
                setWrapText(true);
            }
        });
        Button addSignature = new Button("＋ Add");
        Button editSignature = new Button("✏ Edit");
        Button deleteSignature = new Button("🗑 Delete");
        Button insertSignature = new Button("Insert Signature");
        editSignature.setDisable(true);
        deleteSignature.setDisable(true);
        insertSignature.setDisable(true);
        signatureList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            boolean disabled = selected == null;
            editSignature.setDisable(disabled);
            deleteSignature.setDisable(disabled);
            insertSignature.setDisable(disabled);
        });
        addSignature.setOnAction(e -> {
            ReportSignature created = promptForReportSignature(aiStage, null);
            if (created == null) return;
            try {
                ReportSignature saved = saveReportSignature(created);
                signatureData.add(saved);
                signatureData.sort(java.util.Comparator.comparing(
                        ReportSignature::getTitle, String.CASE_INSENSITIVE_ORDER));
                signatureList.getSelectionModel().select(saved);
                statusLabel.setText("SIGNATURE SAVED: " + saved.getTitle());
            } catch (SQLException ex) {
                showError("Signature Save Error", "Could not save signature to the database:\n" + ex.getMessage());
            }
        });
        editSignature.setOnAction(e -> {
            ReportSignature selected = signatureList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            ReportSignature edited = promptForReportSignature(aiStage, selected);
            if (edited == null) return;
            try {
                ReportSignature saved = saveReportSignature(edited);
                int index = signatureData.indexOf(selected);
                if (index >= 0) signatureData.set(index, saved);
                signatureData.sort(java.util.Comparator.comparing(
                        ReportSignature::getTitle, String.CASE_INSENSITIVE_ORDER));
                signatureList.getSelectionModel().select(saved);
                statusLabel.setText("SIGNATURE UPDATED: " + saved.getTitle());
            } catch (SQLException ex) {
                showError("Signature Update Error", "Could not update the selected signature:\n" + ex.getMessage());
            }
        });
        deleteSignature.setOnAction(e -> {
            ReportSignature selected = signatureList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete saved signature \"" + selected.getTitle() + "\"?",
                    ButtonType.YES, ButtonType.NO);
            applyDialogTheme(confirm);
            confirm.initOwner(aiStage);
            confirm.showAndWait().ifPresent(result -> {
                if (result != ButtonType.YES) return;
                try {
                    deleteReportSignature(selected.getId());
                    signatureData.remove(selected);
                    statusLabel.setText("SIGNATURE DELETED: " + selected.getTitle());
                } catch (SQLException ex) {
                    showError("Signature Delete Error", "Could not delete the selected signature:\n" + ex.getMessage());
                }
            });
        });
        Runnable insertSelectedSignature = () -> {
            ReportSignature selected = signatureList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            insertTextAtSavedEditorSelection(editorEngine, selected.getText());
            statusLabel.setText("SIGNATURE INSERTED: " + selected.getTitle());
        };
        insertSignature.setOnAction(e -> insertSelectedSignature.run());
        signatureList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) insertSelectedSignature.run();
        });
        FlowPane signatureActions = new FlowPane(6, 6, addSignature, editSignature, deleteSignature, insertSignature);
        VBox signaturesPanel = new VBox(8, signaturesHeading, signaturesHint, signatureActions, signatureList);
        signaturesPanel.setPadding(new Insets(10));
        signaturesPanel.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #444;");
        VBox.setVgrow(signatureList, Priority.ALWAYS);
        Tab signaturesTab = new Tab("Signatures", signaturesPanel);
        signaturesTab.setClosable(false);

        TabPane libraryTabs = new TabPane(shortcutsTab, templatesTab, signaturesTab);
        libraryTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        libraryTabs.setMinWidth(290);
        libraryTabs.setPrefWidth(360);

        SplitPane assistantWorkspace = new SplitPane(reportPanel, libraryTabs);
        assistantWorkspace.setDividerPositions(0.77);
        Scene scene = new Scene(assistantWorkspace, 1600, 900);
        scene.setFill(Color.web("#1a1a1a"));
        scene.setOnKeyPressed(event -> {
            if ((event.isControlDown() || event.isMetaDown())
                    && event.getCode() == javafx.scene.input.KeyCode.V
                    && Clipboard.getSystemClipboard().hasImage()) {
                editorImageBridge.pasteKeyImage();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.F) {
                libraryTabs.getSelectionModel().select(templatesTab);
                templateSearch.requestFocus();
                templateSearch.selectAll();
                event.consume();
            } else if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE
                    && libraryTabs.getSelectionModel().getSelectedItem() == templatesTab
                    && !templateSearch.getText().isEmpty()) {
                templateSearch.clear();
                event.consume();
            }
        });
        aiStage.setScene(scene);
        
        // Cleanup WebViews when AI assistant closes to free memory
        aiStage.setOnCloseRequest(e -> {
            try {
                if (editorWebView != null && editorWebView.getEngine() != null) {
                    editorWebView.getEngine().loadContent("");
                }
            } catch (Exception ex) {
            }
        });
        
        aiStage.show();
    }
    
    // Convert DOCX to editable HTML while retaining Word's visible paragraph and run formatting.
    private String convertDocxToHtml(File docxFile) {
        try {
            // Legacy binary .doc files need POI's HWPF reader; XWPF is .docx-only.
            if (docxFile.getName().toLowerCase().endsWith(".doc") && !docxFile.getName().toLowerCase().endsWith(".docx")) {
                return convertLegacyDocToHtml(docxFile);
            }
            try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument(new FileInputStream(docxFile))) {
            StringBuilder html = new StringBuilder();
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph : document.getParagraphs()) {
                boolean isList = paragraph.getNumID() != null;
                boolean isOrderedList = isList && "decimal".equalsIgnoreCase(paragraph.getNumFmt());
                String tag = isList ? "li" : "p";
                if (isList) html.append(isOrderedList ? "<ol style='margin:0; padding-left:36px'>" : "<ul style='list-style-type:disc; margin:0; padding-left:36px'>");
                html.append('<').append(tag).append(" style='").append(wordParagraphCss(paragraph)).append("'>");
                if (paragraph.getRuns().isEmpty()) html.append("&nbsp;");
                for (org.apache.poi.xwpf.usermodel.XWPFRun run : paragraph.getRuns()) {
                    html.append("<span style='").append(wordRunCss(run)).append("'>");
                    html.append(escapeHtml(run.text()));
                    for (int i = 0; i < run.getCTR().sizeOfBrArray(); i++) html.append("<br>");
                    html.append("</span>");
                    for (org.apache.poi.xwpf.usermodel.XWPFPicture picture : run.getEmbeddedPictures()) {
                        html.append(wordPictureHtml(picture));
                    }
                }
                html.append("</").append(tag).append('>');
                if (isList) html.append(isOrderedList ? "</ol>" : "</ul>");
            }
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : document.getTables()) {
                html.append("<table style='border-collapse:collapse; width:100%'>");
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    html.append("<tr>");
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        html.append("<td style='padding:8px; border:1px solid #ddd'>");
                        for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : cell.getParagraphs()) {
                            html.append("<p style='").append(wordParagraphCss(p)).append("'>");
                            for (org.apache.poi.xwpf.usermodel.XWPFRun run : p.getRuns()) {
                                html.append("<span style='").append(wordRunCss(run)).append("'>").append(escapeHtml(run.text())).append("</span>");
                                for (org.apache.poi.xwpf.usermodel.XWPFPicture picture : run.getEmbeddedPictures()) {
                                    html.append(wordPictureHtml(picture));
                                }
                            }
                            html.append("</p>");
                        }
                        html.append("</td>");
                    }
                    html.append("</tr>");
                }
                html.append("</table>");
            }
            return html.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "<p style='color:red'>Error loading Word document: " + escapeHtml(e.getMessage()) + "</p>";
        }
    }

    private String convertLegacyDocToHtml(File docFile) throws Exception {
        try (org.apache.poi.hwpf.HWPFDocument document = new org.apache.poi.hwpf.HWPFDocument(new FileInputStream(docFile))) {
            org.w3c.dom.Document htmlDocument = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().newDocument();
            org.apache.poi.hwpf.converter.WordToHtmlConverter converter = new org.apache.poi.hwpf.converter.WordToHtmlConverter(htmlDocument);
            converter.processDocument(document);
            java.io.StringWriter writer = new java.io.StringWriter();
            javax.xml.transform.Transformer transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.METHOD, "html");
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.transform(new javax.xml.transform.dom.DOMSource(converter.getDocument()), new javax.xml.transform.stream.StreamResult(writer));

            // Keep converter CSS with the editable body so Word formatting displays in WebView.
            org.jsoup.nodes.Document parsed = org.jsoup.Jsoup.parse(writer.toString());
            return parsed.head().html() + parsed.body().html();
        }
    }
    
    // Convert editor HTML to DOCX without flattening paragraphs or run formatting.
    private void saveHtmlAsDocx(String htmlContent, File outputFile) {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            org.jsoup.nodes.Document html = org.jsoup.Jsoup.parseBodyFragment(htmlContent);
            inlineClassStyles(html);
            int[] listNumber = {1};
            for (org.jsoup.nodes.Element element : html.body().children()) {
                appendHtmlElement(document, element, false, listNumber);
            }
            
            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                document.write(out);
            }
            
            statusLabel.setText("SAVED AS DOCX: " + outputFile.getName());
            showInfo("Success", "Report saved as DOCX:\n" + outputFile.getAbsolutePath());
            
        } catch (Exception e) {
            reportError("Report Save Error", "Could not save the DOCX report.", e);
        }
    }

    private String wordParagraphCss(org.apache.poi.xwpf.usermodel.XWPFParagraph p) {
        StringBuilder css = new StringBuilder();
        if (p.getAlignment() != null) {
            switch (p.getAlignment()) {
                case CENTER: css.append("text-align:center;"); break;
                case RIGHT: css.append("text-align:right;"); break;
                case BOTH: css.append("text-align:justify;"); break;
                default: css.append("text-align:left;");
            }
        }
        if (p.getIndentationLeft() > 0) css.append("margin-left:").append(p.getIndentationLeft() / 20).append("pt;");
        if (p.getIndentationRight() > 0) css.append("margin-right:").append(p.getIndentationRight() / 20).append("pt;");
        if (p.getIndentationFirstLine() != 0) css.append("text-indent:").append(p.getIndentationFirstLine() / 20).append("pt;");
        if (p.getSpacingBefore() > 0) css.append("margin-top:").append(p.getSpacingBefore() / 20).append("pt;");
        if (p.getSpacingAfter() > 0) css.append("margin-bottom:").append(p.getSpacingAfter() / 20).append("pt;");
        if (p.getSpacingBetween() > 0) css.append("line-height:").append(p.getSpacingBetween()).append(";");
        if (p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetShd()) {
            String fill = String.valueOf(p.getCTP().getPPr().getShd().getFill());
            if (fill != null && !"auto".equalsIgnoreCase(fill)) css.append("background-color:#").append(fill).append(';');
        }
        return css.toString();
    }

    private String wordRunCss(org.apache.poi.xwpf.usermodel.XWPFRun run) {
        StringBuilder css = new StringBuilder();
        if (run.isBold()) css.append("font-weight:bold;");
        if (run.isItalic()) css.append("font-style:italic;");
        if (run.getUnderline() != null && run.getUnderline() != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE) css.append("text-decoration:underline;");
        if (run.isStrikeThrough()) css.append("text-decoration:line-through;");
        if (run.getFontFamily() != null) css.append("font-family:'").append(run.getFontFamily().replace("'", "")).append("';");
        if (run.getFontSize() > 0) css.append("font-size:").append(run.getFontSize()).append("pt;");
        if (run.getColor() != null) css.append("color:#").append(run.getColor()).append(';');
        return css.toString();
    }

    private String wordPictureHtml(org.apache.poi.xwpf.usermodel.XWPFPicture picture) {
        org.apache.poi.xwpf.usermodel.XWPFPictureData data = picture.getPictureData();
        String mimeType;
        if (data.getPictureType() == org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG) mimeType = "image/jpeg";
        else if (data.getPictureType() == org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_GIF) mimeType = "image/gif";
        else if (data.getPictureType() == org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_BMP) mimeType = "image/bmp";
        else mimeType = "image/png";
        String encoded = java.util.Base64.getEncoder().encodeToString(data.getData());
        return "<img alt=\"Key image\" src=\"data:" + mimeType + ";base64," + encoded
                + "\" style=\"width:50%;height:auto;display:block;margin:15px 0;\">";
    }

    private void appendHtmlElement(org.apache.poi.xwpf.usermodel.XWPFDocument document, org.jsoup.nodes.Element element, boolean listItem, int[] listNumber) {
        String tag = element.tagName().toLowerCase();
        if ("ul".equals(tag) || "ol".equals(tag)) {
            boolean ordered = "ol".equals(tag);
            int[] number = {1};
            for (org.jsoup.nodes.Element child : element.children()) if ("li".equalsIgnoreCase(child.tagName())) appendHtmlElement(document, child, ordered, number);
            return;
        }
        if ("table".equals(tag)) {
            java.util.List<org.jsoup.nodes.Element> rows = new java.util.ArrayList<>();
            for (org.jsoup.nodes.Element child : element.children()) {
                if ("tr".equalsIgnoreCase(child.tagName())) rows.add(child);
                else if ("tbody".equalsIgnoreCase(child.tagName())) rows.addAll(child.children());
            }
            if (!rows.isEmpty()) {
                int columns = 0;
                for (org.jsoup.nodes.Element row : rows) columns = Math.max(columns, row.childrenSize());
                org.apache.poi.xwpf.usermodel.XWPFTable table = document.createTable(rows.size(), columns);
                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    org.jsoup.nodes.Element row = rows.get(rowIndex);
                    org.apache.poi.xwpf.usermodel.XWPFTableRow targetRow = table.getRow(rowIndex);
                for (int i = 0; i < row.childrenSize(); i++) {
                    org.apache.poi.xwpf.usermodel.XWPFTableCell cell = targetRow.getCell(i);
                    cell.removeParagraph(0);
                    for (org.jsoup.nodes.Element child : row.child(i).children()) {
                        org.apache.poi.xwpf.usermodel.XWPFParagraph p = cell.addParagraph();
                        applyParagraphCss(p, child);
                        appendHtmlRuns(p, child, new java.util.HashMap<String, String>());
                    }
                }
            }
            }
            return;
        }
        org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = document.createParagraph();
        applyParagraphCss(paragraph, element);
        if ("li".equals(tag)) {
            if (paragraph.getIndentationLeft() == 0) paragraph.setIndentationLeft(720);
            if (paragraph.getIndentationHanging() == 0) paragraph.setIndentationHanging(360);
            paragraph.createRun().setText(listItem ? (listNumber[0]++) + ". " : "• ");
        }
        appendHtmlRuns(paragraph, element, new java.util.HashMap<String, String>());
    }

    private void appendHtmlRuns(org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph, org.jsoup.nodes.Node node, java.util.Map<String, String> inherited) {
        if (node instanceof org.jsoup.nodes.TextNode) {
            String text = ((org.jsoup.nodes.TextNode) node).getWholeText();
            if (!text.isEmpty()) {
                org.apache.poi.xwpf.usermodel.XWPFRun run = paragraph.createRun();
                applyRunCss(run, inherited);
                run.setText(text);
            }
            return;
        }
        if (!(node instanceof org.jsoup.nodes.Element)) return;
        org.jsoup.nodes.Element element = (org.jsoup.nodes.Element) node;
        if ("img".equalsIgnoreCase(element.tagName())) {
            appendHtmlImage(paragraph, element);
            return;
        }
        if ("br".equalsIgnoreCase(element.tagName())) { paragraph.createRun().addBreak(); return; }
        java.util.Map<String, String> style = new java.util.HashMap<>(inherited);
        style.putAll(cssDeclarations(element.attr("style")));
        String tag = element.tagName().toLowerCase();
        if ("b".equals(tag) || "strong".equals(tag)) style.put("font-weight", "bold");
        if ("i".equals(tag) || "em".equals(tag)) style.put("font-style", "italic");
        if ("u".equals(tag)) style.put("text-decoration", "underline");
        for (org.jsoup.nodes.Node child : element.childNodes()) appendHtmlRuns(paragraph, child, style);
    }

    private void appendHtmlImage(org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph,
                                 org.jsoup.nodes.Element imageElement) {
        String source = imageElement.attr("src");
        if (!source.startsWith("data:image/") || !source.contains(",")) {
            throw new IllegalArgumentException("DOCX images must be embedded in the report. Reinsert this image from a local file.");
        }
        int separator = source.indexOf(',');
        String metadata = source.substring(0, separator).toLowerCase();
        byte[] imageBytes;
        try {
            imageBytes = java.util.Base64.getDecoder().decode(source.substring(separator + 1));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("The report contains an invalid embedded image.", ex);
        }

        int pictureType;
        if (metadata.contains("image/png")) pictureType = org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG;
        else if (metadata.contains("image/jpeg") || metadata.contains("image/jpg")) pictureType = org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG;
        else if (metadata.contains("image/gif")) pictureType = org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_GIF;
        else if (metadata.contains("image/bmp")) pictureType = org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_BMP;
        else throw new IllegalArgumentException("DOCX export supports PNG, JPEG, GIF, or BMP key images.");

        java.awt.image.BufferedImage decoded;
        try {
            decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read the embedded report image.", ex);
        }
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
            throw new IllegalArgumentException("The embedded report image is empty or unsupported.");
        }

        java.util.Map<String, String> imageStyle = cssDeclarations(imageElement.attr("style"));
        String imageFormat = imageElement.attr("data-image-format");
        if ("border".equals(imageFormat) || "frame".equals(imageFormat)) {
                int borderSize = "frame".equals(imageFormat) ? 8 : 1;
                int padding = "frame".equals(imageFormat) ? 4 : 0;
                java.awt.Color borderColor = "frame".equals(imageFormat) ? java.awt.Color.WHITE : java.awt.Color.GRAY;
                java.awt.Color backgroundColor = "frame".equals(imageFormat) ? java.awt.Color.WHITE : new java.awt.Color(0, 0, 0, 0);
                int inset = borderSize + padding;
                java.awt.image.BufferedImage framed = new java.awt.image.BufferedImage(
                        decoded.getWidth() + inset * 2, decoded.getHeight() + inset * 2,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D graphics = framed.createGraphics();
                graphics.setColor(borderColor);
                graphics.fillRect(0, 0, framed.getWidth(), framed.getHeight());
                graphics.setColor(backgroundColor);
                graphics.fillRect(borderSize, borderSize, framed.getWidth() - borderSize * 2,
                        framed.getHeight() - borderSize * 2);
                graphics.drawImage(decoded, inset, inset, null);
                graphics.dispose();
                try (ByteArrayOutputStream framedBytes = new ByteArrayOutputStream()) {
                    javax.imageio.ImageIO.write(framed, "png", framedBytes);
                    imageBytes = framedBytes.toByteArray();
                    decoded = framed;
                    pictureType = org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG;
                } catch (IOException ex) {
                    throw new IllegalArgumentException("Could not format the key image for DOCX.", ex);
                }
        }

        String cssWidth = imageStyle.get("width");
        if ("auto".equalsIgnoreCase(imageStyle.get("margin-left"))
                && "auto".equalsIgnoreCase(imageStyle.get("margin-right"))) {
            paragraph.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
        } else if ("auto".equalsIgnoreCase(imageStyle.get("margin-left"))) {
            paragraph.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.RIGHT);
        } else if ("auto".equalsIgnoreCase(imageStyle.get("margin-right"))) {
            paragraph.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT);
        }
        double widthInches;
        if (cssWidth != null && cssWidth.endsWith("%")) {
            double percent = Double.parseDouble(cssWidth.substring(0, cssWidth.length() - 1));
            widthInches = 6.0 * Math.max(1.0, Math.min(100.0, percent)) / 100.0;
        } else if (cssWidth != null && cssWidth.endsWith("px")) {
            double pixels = Double.parseDouble(cssWidth.substring(0, cssWidth.length() - 2));
            widthInches = pixels / 96.0;
        } else if (cssWidth != null && cssWidth.endsWith("pt")) {
            double points = Double.parseDouble(cssWidth.substring(0, cssWidth.length() - 2));
            widthInches = points / 72.0;
        } else {
            widthInches = decoded.getWidth() / 96.0;
        }
        widthInches = Math.max(0.1, Math.min(6.0, widthInches));
        int widthEmu = (int) Math.round(widthInches * 914400);
        int heightEmu = (int) Math.round(widthEmu * (double) decoded.getHeight() / decoded.getWidth());
        String fileName = imageElement.attr("alt").replaceAll("[^A-Za-z0-9._-]", "_");
        if (fileName.isEmpty()) fileName = "key-image";
        if (!fileName.contains(".")) fileName += pictureType == org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG ? ".jpg" : ".png";

        try {
            paragraph.createRun().addPicture(new ByteArrayInputStream(imageBytes), pictureType, fileName, widthEmu, heightEmu);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not embed the key image in the DOCX report.", ex);
        }
    }

    private void applyParagraphCss(org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph, org.jsoup.nodes.Element source) {
        java.util.Map<String, String> css = cssDeclarations(source.attr("style"));
        String alignment = css.get("text-align");
        if ("center".equalsIgnoreCase(alignment)) paragraph.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
        else if ("right".equalsIgnoreCase(alignment)) paragraph.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.RIGHT);
        else if ("justify".equalsIgnoreCase(alignment)) paragraph.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH);
        else paragraph.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT);
        setParagraphLength(css.get("margin-left"), paragraph::setIndentationLeft);
        setParagraphLength(css.get("margin-right"), paragraph::setIndentationRight);
        setParagraphLength(css.get("text-indent"), paragraph::setIndentationFirstLine);
        setParagraphLength(css.get("margin-top"), paragraph::setSpacingBefore);
        setParagraphLength(css.get("margin-bottom"), paragraph::setSpacingAfter);
        String background = css.get("background-color");
        if (background != null) {
            String color = cssColor(background);
            if (color != null) {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd = ppr.isSetShd() ? ppr.getShd() : ppr.addNewShd();
                shd.setFill(color);
            }
        }
    }

    private void applyRunCss(org.apache.poi.xwpf.usermodel.XWPFRun run, java.util.Map<String, String> css) {
        String weight = css.get("font-weight");
        run.setBold("bold".equalsIgnoreCase(weight) || (weight != null && weight.matches("[6-9]00")));
        run.setItalic("italic".equalsIgnoreCase(css.get("font-style")));
        String decoration = css.get("text-decoration");
        if (decoration != null && decoration.contains("underline")) run.setUnderline(org.apache.poi.xwpf.usermodel.UnderlinePatterns.SINGLE);
        if (decoration != null && decoration.contains("line-through")) run.setStrikeThrough(true);
        String family = css.get("font-family");
        if (family != null && !family.isBlank()) run.setFontFamily(family.replace("'", "").replace("\"", "").split(",")[0].trim());
        Integer size = cssPointSize(css.get("font-size"));
        if (size != null) run.setFontSize(size);
        String color = cssColor(css.get("color"));
        if (color != null) run.setColor(color);
    }

    private java.util.Map<String, String> cssDeclarations(String text) {
        java.util.Map<String, String> css = new java.util.HashMap<>();
        if (text == null) return css;
        for (String declaration : text.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon > 0) css.put(declaration.substring(0, colon).trim().toLowerCase(), declaration.substring(colon + 1).trim());
        }
        return css;
    }

    private void inlineClassStyles(org.jsoup.nodes.Document html) {
        java.util.Map<String, String> rules = new java.util.HashMap<>();
        for (org.jsoup.nodes.Element style : html.select("style")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\.([A-Za-z0-9_-]+)\\s*\\{([^}]*)}").matcher(style.data());
            while (matcher.find()) rules.put(matcher.group(1), matcher.group(2));
        }
        for (org.jsoup.nodes.Element element : html.getAllElements()) {
            StringBuilder combined = new StringBuilder();
            for (String className : element.classNames()) {
                String css = rules.get(className);
                if (css != null) combined.append(css).append(';');
            }
            combined.append(element.attr("style"));
            if (combined.length() > 0) element.attr("style", combined.toString());
        }
    }

    private void setParagraphLength(String cssValue, java.util.function.IntConsumer setter) {
        Integer points = cssPointSize(cssValue);
        if (points != null) setter.accept(points * 20);
    }

    private Integer cssPointSize(String value) {
        if (value == null) return null;
        try {
            String normalized = value.trim().toLowerCase();
            double amount = Double.parseDouble(normalized.replaceAll("[^0-9.]", ""));
            return (int) Math.round(normalized.endsWith("px") ? amount * 0.75 : amount);
        } catch (Exception ignored) { return null; }
    }

    private String cssColor(String value) {
        if (value == null) return null;
        String color = value.trim();
        if (color.matches("#[0-9a-fA-F]{6}")) return color.substring(1).toUpperCase();
        if (color.matches("#[0-9a-fA-F]{3}")) return ("" + color.charAt(1) + color.charAt(1) + color.charAt(2) + color.charAt(2) + color.charAt(3) + color.charAt(3)).toUpperCase();
        return null;
    }
    
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&")
                   .replace("<", "<")
                   .replace(">", ">")
                   .replace("\n", "<br>");
    }
    
    private String extractStudyId(String url) {
        try {
            String query = URI.create(url).getQuery();
            if (query != null) {
                for (String parameter : query.split("&")) {
                    String[] parts = parameter.split("=", 2);
                    if (parts.length == 2 && "studyid".equalsIgnoreCase(parts[0]) && !parts[1].trim().isEmpty()) {
                        return parts[1].replaceAll("[^A-Za-z0-9_-]", "");
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            logger.warn("Could not parse the PACS study URL; using a generic filename", e);
        }
        return "PACS";
    }

    private void showLogin(Stage stage) {
        VBox loginBox = new VBox(20);
        loginBox.setAlignment(Pos.CENTER);
        loginBox.setPadding(new Insets(30, 40, 40, 40));
        loginBox.setMaxSize(400, 500);
        
        loginBox.setStyle("-fx-background-color: rgba(30, 30, 30, 0.9); " +
                         "-fx-background-radius: 20; " +
                         "-fx-border-color: rgba(255, 152, 0, 0.3); " +
                         "-fx-border-width: 1; " +
                         "-fx-border-radius: 20;");
        loginBox.setEffect(new DropShadow(30, Color.rgb(0, 0, 0, 0.8)));

        ImageView logoView = new ImageView();
        try {
            InputStream is = getClass().getResourceAsStream("/icon.png");
            if (is != null) {
                logoView.setImage(new Image(is));
                logoView.setFitWidth(80);
                logoView.setPreserveRatio(true);
            }
        } catch (RuntimeException e) {
            logger.warn("Could not load the login logo", e);
        }

        Label title = new Label("ELITERADS PRO");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 28px; -fx-font-weight: bold; -fx-letter-spacing: 2px;");
        
        Label subtitle = new Label("RADIOLOGY WORKSTATION LOGIN");
        subtitle.setStyle("-fx-text-fill: #888; -fx-font-size: 14px;");

        TextField userField = new TextField();
        userField.setPromptText("Username");
        userField.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: white; -fx-prompt-text-fill: #666; -fx-background-radius: 8; -fx-padding: 12; -fx-border-color: #444; -fx-border-radius: 8;");

        PasswordField passField = new PasswordField();
        passField.setPromptText("Password");
        passField.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: white; -fx-prompt-text-fill: #666; -fx-background-radius: 8; -fx-padding: 12; -fx-border-color: #444; -fx-border-radius: 8;");

        Button loginBtn = new Button("AUTHENTICATE");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        loginBtn.setStyle("-fx-background-color: linear-gradient(to bottom, #ffb74d, #ff9800); -fx-text-fill: black; -fx-font-weight: bold; -fx-padding: 12; -fx-background-radius: 8; -fx-cursor: hand;");
        loginBtn.setEffect(new DropShadow(10, Color.rgb(255, 152, 0, 0.4)));

        Button adminBtn = new Button("ADMIN");
        adminBtn.setMaxWidth(Double.MAX_VALUE);
        adminBtn.setStyle("-fx-background-color: #3a3a3a; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 12; -fx-background-radius: 8; -fx-border-color: #555; -fx-border-radius: 8; -fx-cursor: hand;");
        HBox loginActions = new HBox(10, adminBtn, loginBtn);
        loginActions.setAlignment(Pos.CENTER);
        HBox.setHgrow(adminBtn, Priority.ALWAYS);
        HBox.setHgrow(loginBtn, Priority.ALWAYS);
        adminBtn.setOnAction(e -> {
            userField.setText("admin");
            passField.requestFocus();
        });

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #ff4444; -fx-font-size: 12px;");

        loginBtn.setOnAction(e -> {
            String user = userField.getText();
            String pass = passField.getText();
            // Run authentication off the JavaFX thread
            ioExecutor.submit(() -> {
                boolean ok = authenticate(user, pass);
                Platform.runLater(() -> {
                    if (ok) showMainApp(stage);
                    else errorLabel.setText("Access Denied: Invalid Credentials");
                });
            });
        });
        
        passField.setOnAction(e -> loginBtn.fire());

        loginBox.getChildren().addAll(logoView, title, subtitle, userField, passField, loginActions, errorLabel);
        
        StackPane root = new StackPane(loginBox);
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #0d0d0d, #1a1a1a, #000000);");
        
        Scene scene = new Scene(root, 800, 800);
        stage.setScene(scene);
        stage.setTitle("Eliterads Login");
        stage.show();
    }

    private boolean authenticate(String user, String pass) {
        try (Connection conn = getConnection()) {
            PreparedStatement pstmt = conn.prepareStatement("SELECT 1 FROM users WHERE username = ? AND password = ?");
            pstmt.setString(1, user);
            pstmt.setString(2, pass);
            return pstmt.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    @Override
    public void stop() throws Exception {
        // Shutdown executor gracefully
        if (ioExecutor != null) {
            ioExecutor.shutdown();
            try {
                if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    ioExecutor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                ioExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        super.stop();
    }

    public static void main(String[] args) { launch(args); }
}
