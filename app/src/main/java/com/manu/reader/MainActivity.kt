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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
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
        service?.loadBook(uri) ?: run { pendingBook = uri }
    }

    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Library.setFolder(this, uri)
        refreshLibrary()
        drawer.open()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
        findViewById<Button>(R.id.getBooksHelp).setOnClickListener { showGetBooksHelp() }

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        findViewById<ImageButton>(R.id.nightMode).apply {
            setImageResource(if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode)
            contentDescription = getString(if (isDark) R.string.light_mode else R.string.dark_mode)
            setOnClickListener {
                val mode = if (isDark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
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
        findViewById<Button>(R.id.ttsSettings).setOnClickListener { openTtsSettings() }
        findViewById<Button>(R.id.changeEngine).setOnClickListener { showEngines() }
        findViewById<Button>(R.id.openBook).setOnClickListener {
            openDocument.launch(arrayOf("application/epub+zip"))
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
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ReaderService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        refreshEngineState()
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

    private fun bindSetupGuide(view: View, onGoogleSelected: () -> Unit = {}) {
        val engineList = view.findViewById<LinearLayout>(R.id.engineList)
        for (engine in TtsEngines.recommended) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_engine, engineList, false)
            row.findViewById<TextView>(R.id.engineName).text = engine.name
            row.findViewById<TextView>(R.id.engineDescription).setText(engine.description)
            row.findViewById<Button>(R.id.downloadButton).setOnClickListener { openUrl(engine.url) }
            engineList.addView(row)
        }
        view.findViewById<Button>(R.id.checkAgain).setOnClickListener { refreshEngineState() }
        view.findViewById<Button>(R.id.useGoogle).setOnClickListener {
            if (TtsEngines.GOOGLE_PACKAGE !in TtsEngines.installedPackages(this)) {
                Toast.makeText(this, R.string.google_not_installed, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            TtsEngines.select(this, TtsEngines.GOOGLE_PACKAGE)
            refreshEngineState()
            onGoogleSelected()
        }
    }

    private fun showSetupGuide() {
        val guide = LayoutInflater.from(this).inflate(R.layout.setup_guide, null).apply { isVisible = true }
        val dialog = AlertDialog.Builder(this)
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
        service?.loadBook(uri) ?: run { pendingBook = uri }
    }

    private fun showFolderHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_help_title)
            .setMessage(R.string.folder_help)
            .setPositiveButton(R.string.continue_label) { _, _ -> openFolder.launch(Library.suggestedFolder) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun showGetBooksHelp() {
        val density = resources.displayMetrics.density
        val message = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 16f
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.get_books_title)
            .setView(ScrollView(this).apply { addView(message) })
            .setPositiveButton(R.string.get_books_link) { _, _ -> openUrl("https://www.gutenberg.org/") }
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
        dialog.show()
    }

    private fun refreshLibrary() {
        val folder = Library.folder(this)
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
        engineLabel.text = getString(R.string.engine_label, state.engineName ?: "-")
        loading.isVisible = state.loading
        errorText.isVisible = state.error != null
        errorText.text = state.error

        val book = state.book
        controls.isVisible = book != null
        chapterTitle.isVisible = book != null
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
        AlertDialog.Builder(this)
            .setTitle(R.string.chapters)
            .setItems(book.chapters.map { it.title }.toTypedArray()) { _, which ->
                s.seekTo(book.chapters[which].start)
            }
            .show()
    }

    private fun showEngines() {
        val engines = TtsEngines.installedEngines(this)
        val openSource = TtsEngines.recommended.map { it.packageName }.toSet()
        val labels = engines.map {
            if (it.packageName in openSource) getString(R.string.open_source_suffix, it.label) else it.label
        }
        val current = engines.indexOfFirst { it.packageName == TtsEngines.selected(this)?.packageName }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_engine)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                TtsEngines.select(this, engines[which].packageName)
                service?.initTts()
                dialog.dismiss()
            }
            .setNeutralButton(R.string.setup_guide) { _, _ -> showSetupGuide() }
            .show()
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
}
