package com.manu.reader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.provider.DocumentsContract
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var service: ReaderService? = null
    private var stateJob: Job? = null
    // Picker results arrive before the service rebinds, so the choice is held until then.
    private var pendingBook: Uri? = null
    private var libraryMoveDialog: AlertDialog? = null
    private val importer by lazy { ViewModelProvider(this)[ImportViewModel::class.java] }

    private lateinit var setupSection: View
    private lateinit var readerSection: View
    private lateinit var engineLabel: TextView
    private lateinit var bookTitle: TextView
    private lateinit var chapterTitle: TextView
    private lateinit var progressText: TextView
    private lateinit var paragraphText: TextView
    private lateinit var errorText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var playPause: ImageButton
    private lateinit var controls: View
    private lateinit var drawer: DrawerLayout
    private lateinit var libraryFolder: TextView
    private lateinit var libraryEmpty: TextView
    private lateinit var chooseFolder: Button
    private lateinit var libraryAdapter: ArrayAdapter<String>
    private var libraryBooks: List<LibraryBook> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as ReaderService.LocalBinder).service
            service = s
            if (TtsEngines.canStartReading(this@MainActivity)) s.initTts()
            val picked = pendingBook
            if (picked != null) {
                pendingBook = null
                s.loadBook(picked)
            } else if (s.state.value.book == null && !s.state.value.loading) {
                s.lastBookUri()?.takeIf(::hasReadPermission)?.let { s.loadBook(it) }
            }
            stateJob = lifecycleScope.launch { s.state.collect(::render) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        openBook(uri)
    }

    private val saveConverted = registerForActivityResult(object : ActivityResultContracts.CreateDocument(ImportViewModel.EPUB_MIME) {
        override fun createIntent(context: android.content.Context, input: String): Intent =
            super.createIntent(context, input).apply {
                (importer.state.value as? ImportViewModel.State.NeedsDestination)?.let {
                    val initial = runCatching {
                        val id = DocumentsContract.getDocumentId(it.source)
                        if ('/' in id) DocumentsContract.buildDocumentUri(it.source.authority!!, id.substringBeforeLast('/')) else it.source
                    }.getOrDefault(it.source)
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                }
            }
    }) { uri -> importer.save(uri) }

    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Library.setFolder(this, uri)
        refreshLibrary()
        drawer.open()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingBook = savedInstanceState?.getString("pending_book")?.let(Uri::parse)
        importer.restoreExport(savedInstanceState?.getString("export_path"), savedInstanceState?.getString("export_name"), savedInstanceState?.getString("export_source"))
        setContentView(R.layout.activity_main)
        showThemeTransitionIfNeeded()
        for (id in listOf(R.id.root, R.id.libraryPanel)) {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(id)) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        drawer = findViewById(R.id.drawer)
        libraryFolder = findViewById(R.id.libraryFolder)
        libraryEmpty = findViewById(R.id.libraryEmpty)
        chooseFolder = findViewById(R.id.chooseFolder)
        libraryAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        findViewById<ListView>(R.id.libraryList).apply {
            adapter = libraryAdapter
            setOnItemClickListener { _, _, position, _ ->
                libraryBooks.getOrNull(position)?.let { openBook(it.uri) }
                drawer.close()
            }
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { drawer.open() }
        chooseFolder.setOnClickListener { showFolderHelp() }
        findViewById<Button>(R.id.browseLibraryFolder).setOnClickListener { browseLibraryFolder() }
        findViewById<Button>(R.id.getBooksHelp).setOnClickListener { showGetBooksHelp() }
        findViewById<Button>(R.id.mainGetBooksHelp).setOnClickListener { showGetBooksHelp() }
        findViewById<ImageButton>(R.id.sleepTimer).setOnClickListener { showSleepTimer() }

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        findViewById<ImageButton>(R.id.nightMode).apply {
            setImageResource(if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode)
            contentDescription = getString(if (isDark) R.string.light_mode else R.string.dark_mode)
            setOnClickListener {
                val mode = if (isDark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
                ThemeTransition.coverColor = MaterialColors.getColor(
                    this@MainActivity, com.google.android.material.R.attr.colorSurface, Color.BLACK,
                )
                getSharedPreferences(ManuReaderApp.PREFS_UI, MODE_PRIVATE).edit { putInt(ManuReaderApp.KEY_NIGHT_MODE, mode) }
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }

        val closeDrawerOnBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = drawer.close()
        }
        onBackPressedDispatcher.addCallback(this, closeDrawerOnBack)
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                closeDrawerOnBack.isEnabled = true
                refreshLibrary()
            }

            override fun onDrawerClosed(drawerView: View) {
                closeDrawerOnBack.isEnabled = false
            }
        })
        refreshLibrary()

        setupSection = findViewById(R.id.setupSection)
        readerSection = findViewById(R.id.readerSection)
        engineLabel = findViewById(R.id.engineLabel)
        bookTitle = findViewById(R.id.bookTitle)
        chapterTitle = findViewById(R.id.chapterTitle)
        progressText = findViewById(R.id.progressText)
        paragraphText = findViewById(R.id.paragraphText)
        errorText = findViewById(R.id.errorText)
        loading = findViewById(R.id.loading)
        playPause = findViewById(R.id.playPause)
        controls = findViewById(R.id.controls)

        bindSetupGuide(setupSection)
        findViewById<Button>(R.id.changeEngine).setOnClickListener { showEngines() }
        findViewById<Button>(R.id.openBook).setOnClickListener {
            openDocument.launch(arrayOf(ImportViewModel.EPUB_MIME, "application/pdf", ImportViewModel.DOCX_MIME))
        }
        chapterTitle.setOnClickListener { showChapters() }
        playPause.setOnClickListener { service?.togglePlayPause() }
        findViewById<ImageButton>(R.id.prevChapter).setOnClickListener { service?.previousChapter() }
        findViewById<ImageButton>(R.id.nextChapter).setOnClickListener { service?.nextChapter() }
        findViewById<ImageButton>(R.id.prevParagraph).setOnClickListener {
            service?.let { it.seekTo(it.state.value.position - 1) }
        }
        findViewById<ImageButton>(R.id.nextParagraph).setOnClickListener {
            service?.let { it.seekTo(it.state.value.position + 1) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                importer.state.collect { state ->
                    service?.state?.value?.let(::render)
                    when (state) {
                        is ImportViewModel.State.NeedsLibraryMove -> if (libraryMoveDialog == null) {
                            libraryMoveDialog = MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle(R.string.library_move_title)
                                .setMessage(getString(R.string.library_move_message, Library.folderName(state.folder)))
                                .setPositiveButton(R.string.library_move_accept) { _, _ -> importer.answerLibraryMove(true) }
                                .setNegativeButton(R.string.library_move_skip) { _, _ -> importer.answerLibraryMove(false) }
                                .setOnCancelListener { importer.answerLibraryMove(false) }
                                .create().also { dialog ->
                                    dialog.setOnDismissListener { libraryMoveDialog = null }
                                    dialog.show()
                                }
                        }
                        is ImportViewModel.State.LibraryMoveFinished -> {
                            Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                            refreshLibrary()
                            importer.acknowledge()
                            importer.open(state.uri)
                        }
                        is ImportViewModel.State.Done -> {
                            loadEpub(state.uri)
                            if (state.converted) {
                                Toast.makeText(this@MainActivity, R.string.conversion_saved, Toast.LENGTH_LONG).show()
                                refreshLibrary()
                            }
                            importer.acknowledge()
                        }
                        is ImportViewModel.State.NeedsDestination -> if (!importer.destinationRequested) {
                            importer.destinationRequested = true
                            Toast.makeText(this@MainActivity, R.string.conversion_save_message, Toast.LENGTH_LONG).show()
                            saveConverted.launch(state.name)
                        }
                        is ImportViewModel.State.Failed -> {
                            MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle(R.string.conversion_save_title)
                                .setMessage(state.message)
                                .setPositiveButton(R.string.close, null)
                                .show()
                            importer.acknowledge()
                        }
                        else -> Unit
                    }
                }
            }
        }
        if (savedInstanceState == null) handleOpenIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_book", pendingBook?.toString())
        (importer.state.value as? ImportViewModel.State.NeedsDestination)?.let {
            outState.putString("export_path", it.file.absolutePath)
            outState.putString("export_name", it.name)
            outState.putString("export_source", it.source.toString())
        }
        super.onSaveInstanceState(outState)
    }

    private fun handleOpenIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme != "content" && uri.scheme != "file") return
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        ) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        service?.pause()
        importer.openExternal(uri)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ReaderService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        refreshEngineState()
        checkForUpdates()
    }

    override fun onStop() {
        stateJob?.cancel()
        unbindService(connection)
        service = null
        super.onStop()
    }

    private fun refreshEngineState() {
        val ready = TtsEngines.canStartReading(this)
        setupSection.isVisible = !ready
        readerSection.isVisible = ready
        if (ready) service?.initTts()
        if (ready && Library.folder(this) == null && !Library.wasPrompted(this)) {
            Library.markPrompted(this)
            showFolderHelp()
        }
    }

    private fun showThemeTransitionIfNeeded() {
        val coverColor = ThemeTransition.coverColor ?: return
        ThemeTransition.coverColor = null
        val decor = window.decorView as ViewGroup
        val cover = View(this).apply {
            setBackgroundColor(coverColor)
            isClickable = true
            isFocusable = true
        }
        decor.addView(cover, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        cover.animate()
            .alpha(0f)
            .setDuration(THEME_TRANSITION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { decor.removeView(cover) }
            .start()
    }

    private fun bindSetupGuide(view: View, onSetupComplete: () -> Unit = {}) {
        val language = resources.configuration.locales[0].language
        val directVoices = DirectVoices.forLanguage(language)
        val hasDirectVoices = directVoices != null
        if (hasDirectVoices) {
            for (id in listOf(R.id.recommendedEngine, R.id.sherpaRequirement, R.id.installVoice,
                R.id.openVoiceEngine, R.id.checkAgain, R.id.otherVoices)) {
                view.findViewById<View>(id).isVisible = false
            }
            view.findViewById<LinearLayout>(R.id.directVoices).apply {
                isVisible = true
                val arm64 = "arm64-v8a" in Build.SUPPORTED_ABIS
                directVoices.orEmpty().forEach { voice ->
                    addView(Button(this@MainActivity).apply {
                        setText(voice.label)
                        isEnabled = arm64
                        setOnClickListener { openUrl(voice.url) }
                    })
                }
                addView(TextView(this@MainActivity).apply {
                    setText(if (arm64) R.string.voice_direct_replacement else R.string.voice_arm64_unavailable)
                    gravity = android.view.Gravity.CENTER
                })
            }
        }
        val installSteps = view.findViewById<View>(R.id.installSteps)
        view.findViewById<Button>(R.id.showInstallSteps).setOnClickListener {
            installSteps.isVisible = !installSteps.isVisible
        }
        val engineList = view.findViewById<LinearLayout>(R.id.engineList)
        val recommended = TtsEngines.recommended.first()
        view.findViewById<TextView>(R.id.recommendedEngine).text =
            getString(R.string.flow_recommended_voice, recommended.name)
        view.findViewById<Button>(R.id.installVoice).setOnClickListener { openUrl(recommended.url) }
        view.findViewById<Button>(R.id.openVoiceEngine).setOnClickListener {
            val engine = TtsEngines.preferredInstalled(this)
            val launch = engine?.let { packageManager.getLaunchIntentForPackage(it.packageName) }
            if (launch == null) {
                showTextHelp(R.string.flow_need_help, R.string.install_steps)
            } else {
                runCatching { startActivity(launch) }.onFailure { openTtsSettings() }
            }
        }
        view.findViewById<Button>(R.id.otherVoices).setOnClickListener {
            engineList.isVisible = !engineList.isVisible
        }
        view.findViewById<Button>(R.id.voiceHelp).setOnClickListener {
            if (hasDirectVoices) showDirectVoiceHelp()
            else showTextHelp(R.string.flow_need_help, R.string.install_steps)
        }
        view.findViewById<Button>(R.id.softwareFreedom).setOnClickListener {
            showTextHelp(R.string.flow_why_free, R.string.why_open_source)
        }
        for (engine in TtsEngines.recommended) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_engine, engineList, false)
            row.findViewById<TextView>(R.id.engineName).text = engine.name
            row.findViewById<TextView>(R.id.engineDescription).setText(engine.description)
            row.findViewById<Button>(R.id.downloadButton).setOnClickListener { openUrl(engine.url) }
            engineList.addView(row)
        }
        view.findViewById<Button>(R.id.checkAgain).setOnClickListener {
            refreshEngineState()
            if (TtsEngines.canStartReading(this)) onSetupComplete()
            else showTextHelp(R.string.flow_need_help, R.string.install_steps)
        }
        view.findViewById<Button>(R.id.useGoogle).setOnClickListener {
            if (TtsEngines.GOOGLE_PACKAGE !in TtsEngines.installedPackages(this)) {
                Toast.makeText(this, R.string.google_not_installed, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            TtsEngines.select(this, TtsEngines.GOOGLE_PACKAGE)
            refreshEngineState()
            onSetupComplete()
        }
    }

    private fun showDirectVoiceHelp() {
        val message = TextView(this).apply {
            setText(R.string.voice_direct_help)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flow_need_help)
            .setView(ScrollView(this).apply { addView(message) })
            .setPositiveButton(R.string.voice_install_sherpa) { _, _ -> openUrl(TtsEngines.recommended.first().url) }
            .setNeutralButton(R.string.voice_install_fdroid) { _, _ -> openUrl("https://f-droid.org/") }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showSetupGuide() {
        val guide = LayoutInflater.from(this).inflate(R.layout.setup_guide, null).apply {
            isVisible = true
            findViewById<View>(R.id.installSteps).isVisible = true
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(ScrollView(this).apply {
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, 0)
                addView(guide)
            })
            .setNegativeButton(R.string.close, null)
            .create()
        bindSetupGuide(guide) { dialog.dismiss() }
        dialog.show()
    }

    private fun openBook(uri: Uri) {
        service?.pause()
        importer.open(uri)
    }

    private fun loadEpub(uri: Uri) {
        service?.loadBook(uri) ?: run { pendingBook = uri }
    }

    private fun showFolderHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flow_library_title)
            .setMessage(R.string.flow_library_intro)
            .setPositiveButton(R.string.choose_folder) { _, _ -> openFolder.launch(Library.suggestedFolder) }
            .setNegativeButton(R.string.later, null)
            .setNeutralButton(R.string.flow_need_help) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.folder_help_title)
                    .setMessage(R.string.folder_help)
                    .setPositiveButton(R.string.choose_folder) { _, _ -> openFolder.launch(Library.suggestedFolder) }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
            .show()
    }

    private fun showGetBooksHelp() {
        val density = resources.displayMetrics.density
        lateinit var booksDialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = (24 * density).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.flow_books_intro)
                gravity = android.view.Gravity.CENTER
                textSize = 16f
                setPadding(0, 0, 0, (8 * density).toInt())
            })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.flow_find_books)
                setOnClickListener {
                    booksDialog.dismiss()
                    openUrl(getString(R.string.get_books_url))
                }
            })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.flow_search_by_title)
                setOnClickListener {
                    booksDialog.dismiss()
                    showBookTitleSearch()
                }
            })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.flow_why_books_free)
                setOnClickListener {
                    booksDialog.dismiss()
                    showGetBooksDetails()
                }
            })
        }
        booksDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flow_books_title)
            .setView(content)
            .setNegativeButton(R.string.close, null)
            .create()
        booksDialog.show()
    }

    private fun showBookTitleSearch() {
        val input = EditText(this).apply {
            hint = getString(R.string.flow_search_title_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val provider = Spinner(this, Spinner.MODE_DROPDOWN).apply {
            adapter = BookSearchProviderAdapter(this@MainActivity)
            contentDescription = getString(R.string.search_provider)
            setSelection(0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(provider, LinearLayout.LayoutParams(
                (96 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flow_search_title_dialog)
            .setView(content)
            .setPositiveButton(R.string.flow_search_by_title, null)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = input.text.toString().trim()
                if (title.isEmpty()) {
                    input.error = getString(R.string.flow_search_title_hint)
                    return@setOnClickListener
                }
                val selected = provider.selectedItem as BookSearchProvider
                val uri = selected.searchUri(title)
                runCatching { CustomTabsIntent.Builder().build().launchUrl(this, uri) }
                    .onFailure { openUrl(uri.toString()) }
                dialog.dismiss()
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun showSleepTimer() {
        val reader = service ?: return
        val deadline = reader.state.value.sleepDeadline
        val minutes = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                (1..30).map { getString(R.string.sleep_timer_minutes, it) })
            contentDescription = getString(R.string.sleep_timer_title)
            val remaining = deadline?.let {
                ((it - android.os.SystemClock.elapsedRealtime() + 59_999) / 60_000).toInt().coerceIn(1, 30)
            } ?: 15
            setSelection(remaining - 1)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, padding)
            addView(minutes)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.sleep_timer_info)
                gravity = android.view.Gravity.CENTER
                textSize = 14f
            })
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer_intro)
            .setView(content)
            .setPositiveButton(R.string.sleep_timer_start) { _, _ ->
                reader.setSleepTimer(minutes.selectedItemPosition + 1)
            }
            .setNegativeButton(R.string.close, null)
        if (deadline != null) builder.setNeutralButton(R.string.sleep_timer_cancel) { _, _ -> reader.cancelSleepTimer() }
        builder.show()
    }

    private fun showTextHelp(title: Int, message: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showGetBooksDetails() {
        val density = resources.displayMetrics.density
        val message = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setTextIsSelectable(true)
            textSize = 16f
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.get_books_title)
            .setView(ScrollView(this).apply { addView(message) })
            .setPositiveButton(R.string.get_books_link) { _, _ -> openUrl(getString(R.string.get_books_url)) }
            .setNegativeButton(R.string.close, null)
            .create()

        // Hidden lines use the dialog's own surface color, so they only show when selected, in light or dark mode.
        val surface = MaterialColors.getColor(dialog.context, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.TRANSPARENT)
        message.setBackgroundColor(surface)
        message.text = SpannableStringBuilder(getString(R.string.get_books_message)).append("\n\n\n").apply {
            val start = length
            append(getString(R.string.get_books_hidden))
            setSpan(ForegroundColorSpan(surface), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val linkedText = SpannableStringBuilder(message.text)
        val calibreUrl = "https://calibre-ebook.com/download"
        val calibreStart = linkedText.indexOf(calibreUrl)
        if (calibreStart >= 0) {
            linkedText.setSpan(android.text.style.URLSpan(calibreUrl), calibreStart,
                calibreStart + calibreUrl.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            message.text = linkedText
            message.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        dialog.show()
    }

    private fun browseLibraryFolder() {
        val tree = Library.folder(this) ?: return
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.browse_library_unavailable, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.browse_library_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshLibrary() {
        val folder = Library.folder(this)
        findViewById<Button>(R.id.browseLibraryFolder).isEnabled = folder != null
        chooseFolder.setText(if (folder == null) R.string.choose_folder else R.string.change_folder)
        if (folder == null) {
            libraryFolder.setText(R.string.no_folder)
            showLibrary(emptyList(), showEmptyHint = false)
            return
        }
        libraryFolder.text = getString(R.string.folder_label, Library.folderName(folder))
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) { runCatching { Library.list(this@MainActivity, folder) }.getOrDefault(emptyList()) }
            showLibrary(books, showEmptyHint = true)
        }
    }

    private fun showLibrary(books: List<LibraryBook>, showEmptyHint: Boolean) {
        libraryBooks = books
        libraryAdapter.clear()
        libraryAdapter.addAll(books.map { it.name })
        libraryEmpty.setText(R.string.library_empty)
        libraryEmpty.isVisible = showEmptyHint && books.isEmpty()
    }

    private fun render(state: ReaderService.State) {
        findViewById<ImageButton>(R.id.sleepTimer).apply {
            isEnabled = state.book != null && !state.loading
            isActivated = state.sleepDeadline != null
            contentDescription = getString(if (state.sleepDeadline != null) R.string.sleep_timer_active else R.string.sleep_timer_title)
        }
        engineLabel.text = getString(R.string.engine_label, state.engineName ?: "-")
        val converting = importer.state.value == ImportViewModel.State.Busy
        loading.isVisible = state.loading || converting
        findViewById<Button>(R.id.openBook).isEnabled = !converting
        errorText.isVisible = state.error != null
        errorText.text = state.error
        if (converting) {
            errorText.isVisible = true
            errorText.setText(R.string.conversion_busy)
        }

        val book = state.book
        controls.isVisible = book != null
        chapterTitle.isVisible = book != null
        findViewById<Button>(R.id.mainGetBooksHelp).isVisible = book == null && !state.loading && !converting
        if (book == null) {
            bookTitle.setText(R.string.no_book)
            progressText.text = ""
            paragraphText.text = ""
            return
        }
        val position = state.position.coerceAtMost(book.paragraphs.lastIndex)
        val chapter = book.chapterIndexOf(position)
        bookTitle.text = book.title
        chapterTitle.text = getString(R.string.chapter_label, book.chapters[chapter].title)
        progressText.text = getString(
            R.string.progress_label, chapter + 1, book.chapters.size,
            position * 100 / book.paragraphs.size.coerceAtLeast(1),
        )
        paragraphText.text = book.paragraphs[position]
        playPause.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        playPause.contentDescription = getString(if (state.playing) R.string.pause else R.string.play)
    }

    private fun showChapters() {
        val s = service ?: return
        val book = s.state.value.book ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chapters)
            .setItems(book.chapters.map { it.title }.toTypedArray()) { _, which ->
                s.seekTo(book.chapters[which].start)
            }
            .show()
    }

    private var updateCheckRunning = false
    private var pendingUpdate: AppUpdate? = null
    private var updateDialog: AlertDialog? = null

    private fun checkForUpdates() {
        if (updateDialog != null || updateCheckRunning) return
        fun offer(update: AppUpdate) {
            updateDialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_title)
                .setMessage(getString(R.string.update_message, update.version))
                .setPositiveButton(R.string.update_download) { _, _ -> openUrl(update.apkUrl) }
                .setNegativeButton(R.string.later, null)
                .create().also { dialog ->
                    dialog.setOnDismissListener {
                        pendingUpdate = null
                        updateDialog = null
                    }
                    dialog.show()
                }
        }
        pendingUpdate?.let { offer(it); return }
        val preferences = getSharedPreferences("updates", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previous = preferences.getLong("last_check", 0)
        if (now >= previous && now - previous < 24 * 60 * 60 * 1000L) return
        preferences.edit { putLong("last_check", now) }
        updateCheckRunning = true
        lifecycleScope.launch {
            try {
                val current = packageManager.getPackageInfo(packageName, 0).versionName ?: return@launch
                val update = withContext(Dispatchers.IO) { runCatching { AppUpdates.fetch(current) }.getOrNull() }
                if (update != null) {
                    pendingUpdate = update
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) offer(update)
                }
            } finally {
                updateCheckRunning = false
            }
        }
    }

    private fun showVoicePitch() {
        val reader = service ?: return
        var pitch = reader.voicePitch
        var speed = reader.voiceSpeed
        val label = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            text = getString(R.string.voice_pitch_value, pitch)
        }
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 0.5f
            valueTo = 2f
            stepSize = 0.1f
            value = (kotlin.math.round(pitch * 10) / 10).coerceIn(valueFrom, valueTo)
            contentDescription = getString(R.string.voice_pitch_title)
            addOnChangeListener { _, value, _ ->
                pitch = value
                label.text = getString(R.string.voice_pitch_value, value)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(label)
            addView(slider)
            val speedLabel = TextView(this@MainActivity).apply {
                gravity = android.view.Gravity.CENTER
                text = getString(R.string.voice_speed_value, speed)
            }
            addView(speedLabel)
            addView(com.google.android.material.slider.Slider(this@MainActivity).apply {
                valueFrom = 0.5f
                valueTo = 2f
                stepSize = 0.1f
                value = (kotlin.math.round(speed * 10) / 10).coerceIn(valueFrom, valueTo)
                contentDescription = getString(R.string.voice_speed_label)
                addOnChangeListener { _, value, _ ->
                    speed = value
                    speedLabel.text = getString(R.string.voice_speed_value, value)
                }
            })
            addView(TextView(this@MainActivity).apply {
                setText(R.string.voice_settings_hint)
                gravity = android.view.Gravity.CENTER
            })
        }
        fun applySettings(pitchValue: Float, speedValue: Float) {
            if (!reader.setVoiceSettings(pitchValue, speedValue)) {
                Toast.makeText(this, R.string.voice_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.voice_settings_title)
            .setView(content)
            .setPositiveButton(R.string.voice_pitch_apply) { _, _ -> applySettings(pitch, speed) }
            .setNeutralButton(R.string.voice_settings_reset) { _, _ -> applySettings(1f, 1f) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showEngines() {
        val engines = TtsEngines.installedEngines(this)
        val openSource = TtsEngines.recommended.map { it.packageName }.toSet()
        val choices = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        lateinit var dialog: AlertDialog
        val selectedPackage = TtsEngines.selected(this)?.packageName
        engines.forEach { engine ->
            val label = if (engine.packageName in openSource) {
                getString(R.string.open_source_suffix, engine.label)
            } else {
                engine.label
            }
            choices.addView(RadioButton(this).apply {
                text = label
                isChecked = engine.packageName == selectedPackage
                setOnClickListener {
                    TtsEngines.select(this@MainActivity, engine.packageName)
                    service?.initTts()
                    dialog.dismiss()
                }
            })
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(choices)
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.voice_settings_title)
                setOnClickListener {
                    dialog.dismiss()
                    showVoicePitch()
                }
            })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.setup_guide)
                setOnClickListener {
                    dialog.dismiss()
                    showSetupGuide()
                }
            })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.tts_settings)
                setOnClickListener {
                    dialog.dismiss()
                    openTtsSettings()
                }
            })
        }
        val scroll = ScrollView(this).apply { addView(content) }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_engine)
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
    }

    // Also covers books opened from the library folder, whose permission comes from the folder grant.
    private fun hasReadPermission(uri: Uri) =
        runCatching { contentResolver.openFileDescriptor(uri, "r")?.close() }.isSuccess

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    companion object {
        private const val THEME_TRANSITION_DURATION_MS = 1000L
    }
}

private object ThemeTransition {
    var coverColor: Int? = null
}
