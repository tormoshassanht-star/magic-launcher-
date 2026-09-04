package com.hassan.launcher.ui

import android.app.ActivityOptions
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hassan.launcher.R
import com.hassan.launcher.data.AppRepository
import com.hassan.launcher.data.DefaultLayout
import com.hassan.launcher.data.FolderData
import com.hassan.launcher.data.HomeCell
import com.hassan.launcher.data.IconCache
import com.hassan.launcher.data.LayoutPreset
import com.hassan.launcher.data.Prefs
import com.hassan.launcher.databinding.ActivityLauncherBinding
import com.hassan.launcher.databinding.ItemHomeAppBinding
import com.hassan.launcher.model.AppInfo
import com.hassan.launcher.service.GestureAccessibilityService
import com.hassan.launcher.service.NotificationBadgeService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val HOST_ID = 1024
        private const val REQ_BIND = 71
        private const val REQ_CONFIGURE = 72
        private const val REQ_RECONFIGURE = 73
        private const val FOLDER_CAPACITY = 20
    }

    private lateinit var b: ActivityLauncherBinding
    private lateinit var prefs: Prefs
    private var pullActive = false
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var host: LauncherWidgetHost
    private var hostListening = false
    private lateinit var awm: AppWidgetManager
    private var workspaceAdapter: WorkspaceAdapter? = null
    private var dockAdapter: HomeItemAdapter? = null
    private val pageLayouts = HashMap<Int, CellLayout>()
    private val widgetViews = HashMap<Int, WidgetFrame>()
    private var pendingWidgetId = -1

    private var apps: List<AppInfo> = emptyList()
    private var appsByKey: Map<String, AppInfo> = emptyMap()
    private var pages: MutableList<MutableList<HomeCell>> = mutableListOf(mutableListOf())
    private var dock: MutableList<String> = mutableListOf()
    private var folders: MutableMap<String, FolderData> = LinkedHashMap()

    private var sig = ""
    private var sortMode = SortMode.NAME
    private var query = ""
    private var pageHeight = 0
    private var pageWidth = 0
    private var drawerDark = false
    private var popup: PopupWindow? = null
    private var batteryLevel = -1
    private var lastLetter: Char? = null
    private val letters = ('A'..'Z').toList() + '#'
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    private var currentDrag: DragState? = null
    private var hoverView: View? = null
    private var tempPageAdded = false
    private var dragMoveView: View? = null
    private var dragMoveX = 0f
    private var dragMoveY = 0f
    private var drawerClosedForDrag = false
    private val handler = Handler(Looper.getMainLooper())
    private var edgeFlipDir = 0
    private val edgeFlip = Runnable {
        val next = b.workspace.currentItem + edgeFlipDir
        edgeFlipDir = 0
        if (next in pages.indices) b.workspace.setCurrentItem(next, true)
    }
    private var openFolderId: String? = null

    private val gestureHost = object : HomeGestures.Host {
        override fun onPullStart(): Boolean {
            if (currentDrag != null || overviewOpen || openFolderId != null || resizing != null || searchOpen) return false
            dismissPopup()
            pullActive = true
            b.drawer.beginDrag()
            return true
        }

        override fun onPull(dy: Float) {
            if (pullActive) b.drawer.dragBy(dy * 1.7f)
        }

        override fun onPullEnd(velocityY: Float) {
            if (!pullActive) return
            pullActive = false
            b.drawer.settle(velocityY)
        }

        override fun onSwipeDown(fromRight: Boolean) {
            if (currentDrag != null || !prefs.swipeDownNotifications) return
            if (fromRight) openQuickSettings() else openNotifications()
        }

        override fun onDoubleTapEmpty() {
            if (prefs.doubleTapLock) lockScreen()
        }

        override fun onLongPressEmpty() {
            b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showHomeOptions()
        }

        override fun onPinchIn() {
            if (currentDrag == null) openOverview()
        }
    }

    private val drawerState = DrawerState()
    private var badgeCounts: Map<String, Int> = emptyMap()

    private fun badgeForPackage(pkg: String): Int = if (prefs.badges) badgeCounts[pkg] ?: 0 else 0

    private fun badgeFor(item: HomeItem): Int = when (item) {
        is HomeItem.App -> badgeForPackage(item.app.packageName)
        is HomeItem.Folder -> item.apps.map { it.packageName }.distinct().sumOf { badgeForPackage(it) }
        is HomeItem.Widget -> 0
    }

    private fun applyBadges() {
        for (layout in pageLayouts.values) {
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                val item = child.tag as? HomeItem ?: continue
                val badge = child.findViewById<TextView>(R.id.countBadge) ?: continue
                Badges.apply(badge, badgeFor(item))
            }
        }
        dockAdapter?.notifyDataSetChanged()
        b.folderGrid.adapter?.notifyDataSetChanged()
        refreshDrawerViews()
    }

    private fun maybeAskNotificationAccess() {
        if (!prefs.badges || prefs.askedBadges || NotificationBadgeService.isEnabled(this)) return
        prefs.askedBadges = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Show notification counts?")
            .setMessage("To show unread counts on app icons like Honor does, Magic Launcher needs notification access. Turn it on for Magic Launcher on the next screen.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .show()
    }
    private var drawerPages: List<List<DrawerItem>> = emptyList()
    private var overviewOpen = false
    private var dragMoved = false

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateDefaultBanner()
        if (!isDefaultLauncher()) DefaultLauncher.openSettings(this)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
            updateDate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        b = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = Prefs(this)
        IconCache.configure(this, prefs.iconShape, prefs.iconSize)
        AppRepository.init(this)
        awm = AppWidgetManager.getInstance(this)
        host = LauncherWidgetHost(this, HOST_ID)
        startHostListening()
        sortMode = SortMode.of(prefs.drawerSort)
        sig = prefs.configSignature()

        setupInsets()
        setupDrawer()
        setupHomeChrome()
        setupDragTargets()
        setupFolderOverlay()
        setupResize()
        setupSearch()
        b.workspace.doOnLayout {
            pageHeight = it.height
            pageWidth = it.width
            rebuildHome()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppRepository.apps.collect { onApps(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotificationBadgeService.counts.collect {
                    badgeCounts = it
                    applyBadges()
                }
            }
        }
        drawerState.badgeFor = { badgeForPackage(it.packageName) }

        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        startHostListening()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(batteryReceiver)
        try {
            host.stopListening()
        } catch (e: Exception) {
        }
        hostListening = false
    }

    private fun startHostListening() {
        if (hostListening) return
        try {
            host.startListening()
            hostListening = true
        } catch (e: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        updateDate()
        updateDefaultBanner()
        if (!isDefaultLauncher() && !prefs.askedDefault) {
            prefs.askedDefault = true
            requestDefaultLauncher()
        } else {
            maybeAskNotificationAccess()
        }
        sortMode = SortMode.of(prefs.drawerSort)
        val s = prefs.configSignature()
        if (s != sig) {
            sig = s
            IconCache.configure(this, prefs.iconShape, prefs.iconSize)
            (b.drawerList.layoutManager as GridLayoutManager).spanCount = prefs.drawerColumns
            applyDrawerTheme()
            rebuildHome()
        }
        if (!prefs.hasLayout && apps.isNotEmpty()) onApps(apps) else refreshDrawer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dismissPopup()
        when {
            searchOpen -> closeSearch()
            resizing != null -> finishResize()
            overviewOpen -> closeOverview()
            openFolderId != null -> closeFolder()
            b.drawer.isShowing -> closeDrawer()
            else -> b.workspace.setCurrentItem(homePageIndex(), true)
        }
    }

    private fun homePageIndex() = prefs.homePage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingWidgetId
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK && id >= 0) afterBind(id) else discardWidgetId(id)
            REQ_CONFIGURE -> if (resultCode == RESULT_OK && id >= 0) placeWidget(id) else discardWidgetId(id)
        }
        if (requestCode == REQ_BIND || requestCode == REQ_CONFIGURE) pendingWidgetId = -1
    }

    private fun handleBack() {
        dismissPopup()
        if (searchOpen) {
            closeSearch()
            return
        }
        if (resizing != null) {
            finishResize()
            return
        }
        if (overviewOpen) {
            closeOverview()
            return
        }
        if (openFolderId != null) {
            closeFolder()
            return
        }
        if (b.drawer.isShowing) {
            when {
                drawerState.selectionMode -> setSelectionMode(false)
                query.isNotEmpty() -> b.searchInput.setText("")
                else -> closeDrawer()
            }
        } else {
            b.workspace.setCurrentItem(homePageIndex(), true)
        }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            b.header.updatePadding(top = sb.top + dp(6))
            b.homeColumn.updatePadding(bottom = sb.bottom)
            b.removeZone.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = sb.top + dp(10) }
            b.drawerContent.updatePadding(top = sb.top, bottom = max(sb.bottom, ime.bottom))
            b.folderOverlay.updatePadding(top = sb.top, bottom = max(sb.bottom, ime.bottom))
            b.searchContent.updatePadding(top = sb.top, bottom = max(sb.bottom, ime.bottom))
            insets
        }
    }

    private fun setupHomeChrome() {
        val rootGestures = HomeGestures(this, gestureHost) { true }
        b.home.setOnTouchListener { _, e ->
            rootGestures.onTouch(e)
            true
        }
        b.searchBar.setOnClickListener { openSearch() }
        b.searchButton.setOnClickListener { openSearch() }
        b.searchButton.background = GradientDrawable().apply {
            setColor(0x59000000)
            cornerRadius = dp(18).toFloat()
        }
        b.defaultBanner.setOnClickListener { requestDefaultLauncher() }
        b.mic.setOnClickListener {
            try {
                startActivity(Intent("android.speech.action.WEB_SEARCH"))
            } catch (e: Exception) {
                openDrawer(focusSearch = true)
            }
        }
        b.workspace.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateIndicator(position)
        })
        b.dock.addOnItemTouchListener(HomeGestures(this, gestureHost) { e -> b.dock.findChildViewUnder(e.x, e.y) == null })
        b.removeZone.background = GradientDrawable().apply {
            setColor(0xE6E53935.toInt())
            cornerRadius = dp(26).toFloat()
        }
    }

    // ---------------------------------------------------------------- layout model

    private fun cellsOf(keys: List<String>): MutableList<HomeCell> = keys.map { HomeCell(it, -1, -1) }.toMutableList()

    private fun cellWpx(): Float = (pageWidth - 2 * dp(8)) / prefs.columns.toFloat()
    private fun cellHpx(): Float = pageHeight / prefs.rows.toFloat()

    private fun onApps(list: List<AppInfo>) {
        apps = list
        appsByKey = list.associateBy { it.key }
        widgetPackages = null
        if (list.isEmpty()) return
        if (!prefs.hasLayout) {
            if (LayoutPreset.isEmpty(this)) {
                val (p, d) = DefaultLayout.build(this, list)
                pages = p.map { cellsOf(it) }.toMutableList()
                dock = d
                folders = LinkedHashMap()
            } else {
                val r = LayoutPreset.apply(this, list)
                pages = r.pages.map { cellsOf(it) }.toMutableList()
                dock = r.dock
                folders = r.folders
            }
            saveLayout()
        } else {
            pages = prefs.pages
            dock = prefs.dock
            folders = prefs.folders
        }
        if (normalizeLayout()) saveLayout()
        rebuildHome()
        refreshDrawer()
    }

    private fun fixKey(k: String): String? {
        if (HomeItem.isFolderKey(k)) {
            val id = HomeItem.folderId(k)
            val f = folders[id]
            return when {
                f == null || f.apps.isEmpty() -> {
                    folders.remove(id)
                    null
                }
                f.apps.size == 1 -> {
                    folders.remove(id)
                    f.apps[0]
                }
                else -> k
            }
        }
        if (HomeItem.isWidgetKey(k)) {
            val id = HomeItem.widgetId(k)
            return if (id >= 0 && awm.getAppWidgetInfo(id) != null) k else {
                discardWidgetId(id)
                null
            }
        }
        return if (k in appsByKey) k else null
    }

    private fun normalizeLayout(): Boolean {
        var changed = false
        for (f in folders.values) if (f.apps.retainAll { it in appsByKey }) changed = true
        for (page in pages) {
            val it = page.listIterator()
            while (it.hasNext()) {
                val c = it.next()
                val nk = fixKey(c.key)
                when {
                    nk == null -> {
                        it.remove()
                        changed = true
                    }
                    nk != c.key -> {
                        it.set(HomeCell(nk, c.col, c.row))
                        changed = true
                    }
                }
            }
        }
        val dit = dock.listIterator()
        while (dit.hasNext()) {
            val k = dit.next()
            val nk = fixKey(k)
            when {
                nk == null || HomeItem.isWidgetKey(nk) -> {
                    dit.remove()
                    changed = true
                }
                nk != k -> {
                    dit.set(nk)
                    changed = true
                }
            }
        }
        val referenced = (pages.flatten().map { it.key } + dock).filter { HomeItem.isFolderKey(it) }.map { HomeItem.folderId(it) }.toSet()
        if (folders.keys.retainAll(referenced)) changed = true
        if (pages.isEmpty()) {
            pages.add(mutableListOf())
            changed = true
        }
        for (page in pages) if (normalizePage(page)) changed = true
        return changed
    }

    private fun normalizePage(cells: MutableList<HomeCell>): Boolean {
        val cols = prefs.columns
        val rows = prefs.rows
        val occ = Array(cols) { BooleanArray(rows) }
        val placed = ArrayList<HomeCell>()
        val pending = ArrayList<HomeCell>()
        var changed = false
        for (c in cells) {
            if (c.spanX !in 1..cols || c.spanY !in 1..rows) {
                c.spanX = c.spanX.coerceIn(1, cols)
                c.spanY = c.spanY.coerceIn(1, rows)
                changed = true
            }
            if (c.col >= 0 && c.row >= 0 && fits(occ, c.col, c.row, c.spanX, c.spanY)) {
                mark(occ, c.col, c.row, c.spanX, c.spanY)
                placed += c
            } else pending += c
        }
        for (c in pending) {
            changed = true
            val p = findVacant(occ, c.spanX, c.spanY)
            if (p != null) {
                c.col = p.first
                c.row = p.second
                mark(occ, c.col, c.row, c.spanX, c.spanY)
                placed += c
            } else if (HomeItem.isWidgetKey(c.key)) {
                discardWidgetId(HomeItem.widgetId(c.key))
            }
        }
        if (changed) {
            cells.clear()
            cells.addAll(placed)
        }
        return changed
    }

    private fun occupancy(cells: List<HomeCell>, exclude: String?): Array<BooleanArray> {
        val occ = Array(prefs.columns) { BooleanArray(prefs.rows) }
        for (c in cells) if (c.key != exclude) mark(occ, c.col, c.row, c.spanX, c.spanY)
        return occ
    }

    private fun mark(occ: Array<BooleanArray>, col: Int, row: Int, sx: Int, sy: Int) {
        for (x in col until col + sx) for (y in row until row + sy) {
            if (x in occ.indices && y in occ[x].indices) occ[x][y] = true
        }
    }

    private fun fits(occ: Array<BooleanArray>, col: Int, row: Int, sx: Int, sy: Int): Boolean {
        if (col < 0 || row < 0 || col + sx > occ.size || row + sy > occ[0].size) return false
        for (x in col until col + sx) for (y in row until row + sy) if (occ[x][y]) return false
        return true
    }

    private fun findVacant(occ: Array<BooleanArray>, sx: Int, sy: Int): Pair<Int, Int>? {
        for (y in 0 until occ[0].size) for (x in occ.indices) if (fits(occ, x, y, sx, sy)) return x to y
        return null
    }

    private fun nearestVacant(occ: Array<BooleanArray>, sx: Int, sy: Int, fcol: Float, frow: Float): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestD = Float.MAX_VALUE
        for (y in 0 until occ[0].size) for (x in occ.indices) {
            if (!fits(occ, x, y, sx, sy)) continue
            val dx = x - fcol
            val dy = y - frow
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = x to y
            }
        }
        return best
    }

    private fun resolveItem(key: String): HomeItem? = when {
        HomeItem.isFolderKey(key) -> {
            val id = HomeItem.folderId(key)
            val f = folders[id]
            val fApps = f?.apps?.mapNotNull { appsByKey[it] } ?: emptyList()
            if (f == null || fApps.isEmpty()) null else HomeItem.Folder(id, f.name, fApps)
        }
        HomeItem.isWidgetKey(key) -> {
            val id = HomeItem.widgetId(key)
            val info = awm.getAppWidgetInfo(id)
            HomeItem.Widget(id, info, info?.loadLabel(packageManager) ?: "Widget")
        }
        else -> appsByKey[key]?.let { HomeItem.App(it) }
    }

    private fun resolveItems(keys: List<String>): MutableList<HomeItem> = keys.mapNotNull { resolveItem(it) }.toMutableList()

    private fun isOnHome(key: String) =
        pages.any { p -> p.any { it.key == key } } || key in dock || folders.values.any { key in it.apps }

    private fun saveLayout() {
        prefs.pages = pages
        prefs.dock = dock
        prefs.folders = folders
    }

    private fun commitLayout() {
        normalizeLayout()
        saveLayout()
        refreshHome()
    }

    private fun refreshHome() {
        workspaceAdapter?.notifyDataSetChanged()
        updateIndicator(b.workspace.currentItem)
        rebuildDock()
    }

    private fun rebuildHome() {
        if (pageHeight == 0 || apps.isEmpty()) return
        if (normalizeLayout()) saveLayout()
        b.header.isVisible = prefs.showClock
        b.searchButton.isVisible = prefs.searchStyle == "button"
        b.searchBar.isVisible = prefs.searchStyle == "bar"
        val current = if (workspaceAdapter == null) homePageIndex() else b.workspace.currentItem
        pageLayouts.clear()
        val wa = WorkspaceAdapter()
        workspaceAdapter = wa
        b.workspace.adapter = wa
        b.workspace.offscreenPageLimit = max(1, pages.size)
        b.workspace.setCurrentItem(current.coerceIn(0, pages.size - 1), false)
        updateIndicator(b.workspace.currentItem)
        rebuildDock()
    }

    private fun rebuildDock() {
        val items = resolveItems(dock)
        val da = HomeItemAdapter(items, dp(84), false, ::onItemClick, { item, view ->
            showHomeItemPopup(item, view, null)
            startDrag(item, DragSource.Dock, view, (view.parent as? View) ?: view, 1, 1)
        }, ::badgeFor)
        dockAdapter = da
        b.dock.layoutManager = GridLayoutManager(this, items.size.coerceAtLeast(1))
        b.dock.adapter = da
        updateDockVisibility()
    }

    private fun updateDockVisibility() {
        b.dockContainer.isVisible = prefs.dockEnabled && (dock.isNotEmpty() || currentDrag != null)
    }

    private fun updateIndicator(current: Int) {
        b.indicator.removeAllViews()
        if (pages.size <= 1) return
        for (i in pages.indices) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == current) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt())
                }
            }
            val size = if (i == current) dp(8) else dp(6)
            b.indicator.addView(tappableDot(dot, size) { b.workspace.setCurrentItem(i, true) })
        }
    }

    private fun tappableDot(dot: View, size: Int, onTap: () -> Unit): View {
        val wrap = android.widget.FrameLayout(this)
        wrap.addView(dot, android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        wrap.layoutParams = LinearLayout.LayoutParams(dp(18), dp(24))
        wrap.setOnClickListener { onTap() }
        return wrap
    }

    private fun updateDate() {
        val date = dateFormat.format(Date())
        b.dateText.text = if (batteryLevel >= 0) "$date  ·  $batteryLevel%" else date
    }

    // ---------------------------------------------------------------- workspace pages

    private inner class WorkspaceAdapter : RecyclerView.Adapter<WorkspaceAdapter.PageVH>() {

        inner class PageVH(val layout: CellLayout) : RecyclerView.ViewHolder(layout) {
            var pageIndex = 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val layout = CellLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(dp(8), 0, dp(8), 0)
            }
            val vh = PageVH(layout)
            layout.gestures = HomeGestures(this@LauncherActivity, gestureHost) { e -> layout.childAt(e.x, e.y) == null }
            layout.setOnDragListener { _, e -> onPageDrag(vh, e) }
            return vh
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            holder.pageIndex = position
            val layout = holder.layout
            layout.cols = prefs.columns
            layout.rows = prefs.rows
            layout.removeAllViews()
            pageLayouts[position] = layout
            for (cell in pages[position]) {
                val item = resolveItem(cell.key) ?: continue
                val view = when (item) {
                    is HomeItem.Widget -> widgetView(item, position, cell)
                    else -> appCellView(layout, item, position)
                }
                view.tag = item
                (view.parent as? ViewGroup)?.removeView(view)
                layout.addView(view, CellLayout.LayoutParams(cell.col, cell.row, cell.spanX, cell.spanY))
            }
            layout.requestLayout()
        }

        override fun getItemCount() = pages.size
    }

    private fun appCellView(parent: ViewGroup, item: HomeItem, pageIndex: Int): View {
        val vb = ItemHomeAppBinding.inflate(layoutInflater, parent, false)
        val s = IconCache.sizePx
        vb.icon.layoutParams = vb.icon.layoutParams.apply { width = s; height = s }
        vb.label.isVisible = prefs.showHomeLabels
        when (item) {
            is HomeItem.App -> vb.icon.setImageBitmap(IconCache.get(this, item.app))
            is HomeItem.Folder -> vb.icon.setImageBitmap(FolderIcons.render(this, item.apps, s))
            is HomeItem.Widget -> Unit
        }
        vb.label.text = item.label
        Badges.apply(vb.countBadge, badgeFor(item))
        vb.root.setOnClickListener { onItemClick(item, vb.icon) }
        vb.root.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showHomeItemPopup(item, vb.icon, pageIndex)
            startDrag(item, DragSource.Page(pageIndex), vb.icon, vb.root, 1, 1)
            true
        }
        return vb.root
    }

    private fun widgetView(item: HomeItem.Widget, pageIndex: Int, cell: HomeCell): View {
        val frame = widgetViews.getOrPut(item.id) {
            val f = WidgetFrame(this)
            f.setPadding(dp(4), dp(4), dp(4), dp(4))
            val info = item.info
            if (info != null) {
                try {
                    startHostListening()
                    val hv = host.createView(this, item.id, info)
                    f.addView(hv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                } catch (e: Exception) {
                    f.addView(widgetPlaceholder())
                }
            } else {
                f.addView(widgetPlaceholder())
            }
            f
        }
        val density = resources.displayMetrics.density
        val wdp = (cellWpx() * cell.spanX / density).toInt()
        val hdp = (cellHpx() * cell.spanY / density).toInt()
        (frame.getChildAt(0) as? android.appwidget.AppWidgetHostView)?.let {
            try {
                @Suppress("DEPRECATION")
                it.updateAppWidgetSize(null, wdp, hdp, wdp, hdp)
            } catch (e: Exception) {
            }
        }
        frame.onLongPress = {
            showWidgetPopup(item, frame, pageIndex)
            val c = pages.getOrNull(pageIndex)?.firstOrNull { it.key == item.key }
            startDrag(item, DragSource.Page(pageIndex), frame, frame, c?.spanX ?: 1, c?.spanY ?: 1)
        }
        return frame
    }

    private fun widgetPlaceholder(): View = TextView(this).apply {
        text = "Widget unavailable"
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(0x55000000)
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun onItemClick(item: HomeItem, view: View) {
        when (item) {
            is HomeItem.App -> launch(item.app, view)
            is HomeItem.Folder -> openFolder(item.id)
            is HomeItem.Widget -> Unit
        }
    }

    // ---------------------------------------------------------------- widgets

    private var widgetPackages: Set<String>? = null

    private fun hasWidgets(pkg: String): Boolean {
        val set = widgetPackages ?: try {
            awm.installedProviders
                .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }
                .map { it.provider.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }.also { widgetPackages = it }
        return pkg in set
    }

    private fun pickWidget(packageFilter: String? = null) {
        if (pageWidth == 0) return
        WidgetPicker.show(this, cellWpx(), cellHpx(), packageFilter) { addWidget(it) }
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        val bound = try {
            awm.bindAppWidgetIdIfAllowed(id, info.provider)
        } catch (e: Exception) {
            false
        }
        if (bound) {
            afterBind(id)
        } else {
            pendingWidgetId = id
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_BIND)
        }
    }

    private fun afterBind(id: Int) {
        val info = awm.getAppWidgetInfo(id)
        if (info == null) {
            discardWidgetId(id)
            toast("Couldn't add widget")
            return
        }
        if (info.configure != null) {
            pendingWidgetId = id
            try {
                host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
            } catch (e: Exception) {
                placeWidget(id)
            }
        } else {
            placeWidget(id)
        }
    }

    private fun spansFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val sx = ceil(info.minWidth / cellWpx()).toInt().coerceIn(1, prefs.columns)
        val sy = ceil(info.minHeight / cellHpx()).toInt().coerceIn(1, prefs.rows)
        return sx to sy
    }

    private fun placeWidget(id: Int) {
        val info = awm.getAppWidgetInfo(id)
        if (info == null) {
            discardWidgetId(id)
            return
        }
        val (sx, sy) = spansFor(info)
        var page = b.workspace.currentItem.coerceIn(0, pages.size - 1)
        var pos = findVacant(occupancy(pages[page], null), sx, sy)
        if (pos == null) {
            for (i in pages.indices) {
                val p = findVacant(occupancy(pages[i], null), sx, sy)
                if (p != null) {
                    page = i
                    pos = p
                    break
                }
            }
        }
        if (pos == null) {
            pages.add(mutableListOf())
            page = pages.size - 1
            pos = 0 to 0
        }
        pages[page].add(HomeCell(HomeItem.WIDGET_PREFIX + id, pos.first, pos.second, sx, sy))
        saveLayout()
        refreshHome()
        b.workspace.setCurrentItem(page, true)
    }

    private fun discardWidgetId(id: Int) {
        if (id < 0) return
        widgetViews.remove(id)
        try {
            host.deleteAppWidgetId(id)
        } catch (e: Exception) {
        }
    }

    private fun showWidgetPopup(item: HomeItem.Widget, anchor: View, pageIndex: Int) {
        val entries = mutableListOf<PopupEntry>()
        entries += PopupEntry("Resize", R.drawable.ic_apps) { startResize(item, pageIndex) }
        val info = item.info
        if (info != null && info.configure != null && Build.VERSION.SDK_INT >= 28 &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0
        ) {
            entries += PopupEntry("Widget settings", R.drawable.ic_settings) {
                try {
                    host.startAppWidgetConfigureActivityForResult(this, item.id, 0, REQ_RECONFIGURE, null)
                } catch (e: Exception) {
                    toast("Can't open widget settings")
                }
            }
        }
        entries += PopupEntry("Remove", R.drawable.ic_close, destructive = true) {
            pages.getOrNull(pageIndex)?.removeAll { it.key == item.key }
            discardWidgetId(item.id)
            commitLayout()
        }
        dismissPopup()
        popup = AppPopup.show(anchor, item.label, entries)
    }

    private class ResizeSession(val page: Int, val cell: HomeCell, val view: View, val layout: CellLayout)

    private var resizing: ResizeSession? = null
    private var resizeHandle = ResizeFrame.NONE
    private lateinit var resizeFrame: ResizeFrame

    private fun setupResize() {
        resizeFrame = ResizeFrame(this)
        b.resizeOverlay.addView(resizeFrame, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        b.resizeOverlay.setOnTouchListener { _, e ->
            val s = resizing ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeHandle = resizeFrame.handleAt(e.x, e.y)
                    if (resizeHandle == ResizeFrame.NONE && resizeFrame.rect?.contains(e.x, e.y) != true) finishResize()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (resizeHandle != ResizeFrame.NONE) applyResize(s, e.x, e.y)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resizeHandle = ResizeFrame.NONE
                    true
                }
                else -> true
            }
        }
    }

    private fun layoutOrigin(layout: CellLayout): Pair<Float, Float> {
        val a = IntArray(2)
        val o = IntArray(2)
        layout.getLocationInWindow(a)
        b.resizeOverlay.getLocationInWindow(o)
        return (a[0] - o[0]).toFloat() to (a[1] - o[1]).toFloat()
    }

    private fun updateResizeRect(s: ResizeSession) {
        val (ox, oy) = layoutOrigin(s.layout)
        val cw = s.layout.cellW
        val ch = s.layout.cellH
        val left = ox + s.layout.paddingLeft + s.cell.col * cw
        val top = oy + s.layout.paddingTop + s.cell.row * ch
        val inset = dp(4).toFloat()
        resizeFrame.rect = android.graphics.RectF(left + inset, top + inset, left + cw * s.cell.spanX - inset, top + ch * s.cell.spanY - inset)
    }

    private fun startResize(item: HomeItem.Widget, pageIndex: Int) {
        val layout = pageLayouts[pageIndex] ?: return
        val cell = pages.getOrNull(pageIndex)?.firstOrNull { it.key == item.key } ?: return
        val view = (0 until layout.childCount).map { layout.getChildAt(it) }.firstOrNull { (it.tag as? HomeItem)?.key == item.key } ?: return
        dismissPopup()
        resizing = ResizeSession(pageIndex, cell, view, layout)
        b.workspace.isUserInputEnabled = false
        b.resizeOverlay.alpha = 0f
        b.resizeOverlay.isVisible = true
        b.resizeOverlay.animate().alpha(1f).setDuration(150).start()
        updateResizeRect(resizing!!)
    }

    private fun applyResize(s: ResizeSession, x: Float, y: Float) {
        val (ox, oy) = layoutOrigin(s.layout)
        val lx = x - ox - s.layout.paddingLeft
        val ly = y - oy - s.layout.paddingTop
        val cw = s.layout.cellW
        val ch = s.layout.cellH
        val cols = prefs.columns
        val rows = prefs.rows
        val c = s.cell
        var col = c.col
        var row = c.row
        var sx = c.spanX
        var sy = c.spanY
        when (resizeHandle) {
            ResizeFrame.RIGHT -> sx = ((lx / cw).roundToInt() - col).coerceIn(1, cols - col)
            ResizeFrame.BOTTOM -> sy = ((ly / ch).roundToInt() - row).coerceIn(1, rows - row)
            ResizeFrame.LEFT -> {
                val right = col + sx
                col = (lx / cw).roundToInt().coerceIn(0, right - 1)
                sx = right - col
            }
            ResizeFrame.TOP -> {
                val bottom = row + sy
                row = (ly / ch).roundToInt().coerceIn(0, bottom - 1)
                sy = bottom - row
            }
        }
        if (col == c.col && row == c.row && sx == c.spanX && sy == c.spanY) return
        val occ = occupancy(pages[s.page], c.key)
        if (!fits(occ, col, row, sx, sy)) return
        c.col = col
        c.row = row
        c.spanX = sx
        c.spanY = sy
        (s.view.layoutParams as? CellLayout.LayoutParams)?.let {
            it.col = col
            it.row = row
            it.spanX = sx
            it.spanY = sy
        }
        s.view.requestLayout()
        s.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        updateResizeRect(s)
    }

    private fun finishResize() {
        val s = resizing ?: return
        resizing = null
        resizeHandle = ResizeFrame.NONE
        b.workspace.isUserInputEnabled = true
        b.resizeOverlay.animate().alpha(0f).setDuration(150).withEndAction { b.resizeOverlay.isVisible = false }.start()
        saveLayout()
        val density = resources.displayMetrics.density
        val wdp = (cellWpx() * s.cell.spanX / density).toInt()
        val hdp = (cellHpx() * s.cell.spanY / density).toInt()
        ((s.view as? WidgetFrame)?.getChildAt(0) as? android.appwidget.AppWidgetHostView)?.let {
            try {
                @Suppress("DEPRECATION")
                it.updateAppWidgetSize(null, wdp, hdp, wdp, hdp)
            } catch (e: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- drag and drop

    private fun setupDragTargets() {
        b.root.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENDED -> {
                    finishDrag()
                    true
                }
                else -> false
            }
        }
        b.dock.setOnDragListener { _, e -> onDockDrag(e) }
        b.removeZone.setOnDragListener { v, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start()
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val drag = currentDrag ?: return@setOnDragListener false
                    dismissPopup()
                    removeFromSource(drag)
                    when (val item = drag.item) {
                        is HomeItem.Folder -> folders.remove(item.id)
                        is HomeItem.Widget -> discardWidgetId(item.id)
                        else -> Unit
                    }
                    drag.dropped = true
                    commitLayout()
                    true
                }
                else -> true
            }
        }
        b.drawer.setOnDragListener { v, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_LOCATION -> {
                    if (trackDragMove(v, e.x, e.y) && !drawerClosedForDrag && b.drawer.isShowing) {
                        drawerClosedForDrag = true
                        closeDrawer()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun trackDragMove(v: View, x: Float, y: Float): Boolean {
        if (dragMoveView !== v) {
            if (dragMoveView != null) dragMoved = true
            dragMoveView = v
            dragMoveX = x
            dragMoveY = y
            return dragMoved
        }
        val moved = abs(x - dragMoveX) > dp(24) || abs(y - dragMoveY) > dp(24)
        if (moved) dragMoved = true
        if (dragMoved) dismissPopup()
        return dragMoved
    }

    private fun startDrag(
        item: HomeItem,
        source: DragSource,
        shadowView: View,
        ghost: View,
        spanX: Int,
        spanY: Int,
        extras: List<AppInfo> = emptyList(),
    ) {
        if (currentDrag != null) return
        val state = DragState(item, source, ghost, spanX, spanY, extras)
        val scale = if (item is HomeItem.Widget) 1.04f else 1.18f
        val shadow = if (extras.isNotEmpty()) StackShadow(shadowView, extras.size + 1) else LiftShadow(shadowView, scale)
        val started = try {
            shadowView.startDragAndDrop(ClipData.newPlainText("item", item.key), shadow, state, 0)
        } catch (e: Exception) {
            false
        }
        if (!started) return
        currentDrag = state
        dragMoveView = null
        dragMoved = false
        drawerClosedForDrag = false
        ghost.alpha = 0.3f
        if (source is DragSource.Folder) closeFolder()
        if (source !is DragSource.Drawer && pages.last().isNotEmpty()) {
            pages.add(mutableListOf())
            tempPageAdded = true
            workspaceAdapter?.notifyItemInserted(pages.size - 1)
            b.workspace.offscreenPageLimit = max(1, pages.size)
            updateIndicator(b.workspace.currentItem)
        }
        b.removeZone.alpha = 0f
        b.removeZone.isVisible = true
        b.removeZone.animate().alpha(1f).setDuration(150).start()
        updateDockVisibility()
    }

    private fun finishDrag() {
        val drag = currentDrag ?: return
        currentDrag = null
        drag.ghost.alpha = 1f
        clearHover()
        cancelEdgeFlip()
        pageLayouts.values.forEach { it.previewRect = null }
        b.removeZone.isVisible = false
        b.removeZone.scaleX = 1f
        b.removeZone.scaleY = 1f
        if (tempPageAdded) {
            tempPageAdded = false
            if (pages.size > 1 && pages.last().isEmpty()) {
                pages.removeAt(pages.size - 1)
                if (b.workspace.currentItem >= pages.size) b.workspace.setCurrentItem(pages.size - 1, true)
                saveLayout()
                workspaceAdapter?.notifyItemRemoved(pages.size)
                updateIndicator(b.workspace.currentItem)
            }
        }
        updateDockVisibility()
    }

    private fun removeFromSource(drag: DragState) {
        when (val s = drag.source) {
            is DragSource.Page -> pages.getOrNull(s.index)?.removeAll { it.key == drag.item.key }
            DragSource.Dock -> dock.remove(drag.item.key)
            is DragSource.Folder -> folders[s.id]?.apps?.remove(drag.item.key)
            DragSource.Drawer -> Unit
        }
    }

    private fun inFolderZone(child: View, x: Float, y: Float): Boolean {
        val cx = child.left + child.width / 2f
        val cy = child.top + child.height / 2f
        return abs(x - cx) < child.width * 0.28f && abs(y - cy) < child.height * 0.34f
    }

    private fun clearHover() {
        hoverView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
        hoverView = null
    }

    private fun setHover(target: View?) {
        if (target === hoverView) return
        clearHover()
        hoverView = target
        target?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(100)?.start()
    }

    private fun folderTarget(child: View?, drag: DragState, x: Float, y: Float): HomeItem? {
        if (child == null || drag.item !is HomeItem.App) return null
        val target = child.tag as? HomeItem ?: return null
        if (target is HomeItem.Widget || target.key == drag.item.key) return null
        return if (inFolderZone(child, x, y)) target else null
    }

    private fun findDropTarget(layout: CellLayout, cells: List<HomeCell>, drag: DragState, x: Float, y: Float): Pair<Int, Int>? {
        val occ = occupancy(cells, drag.item.key)
        val fcol = (x - layout.paddingLeft) / layout.cellW - drag.spanX / 2f
        val frow = (y - layout.paddingTop) / layout.cellH - drag.spanY / 2f
        val col = fcol.roundToInt().coerceIn(0, (prefs.columns - drag.spanX).coerceAtLeast(0))
        val row = frow.roundToInt().coerceIn(0, (prefs.rows - drag.spanY).coerceAtLeast(0))
        if (fits(occ, col, row, drag.spanX, drag.spanY)) return col to row
        return nearestVacant(occ, drag.spanX, drag.spanY, fcol, frow)
    }

    private fun cancelEdgeFlip() {
        handler.removeCallbacks(edgeFlip)
        edgeFlipDir = 0
    }

    private fun onPageDrag(vh: WorkspaceAdapter.PageVH, e: DragEvent): Boolean {
        val layout = vh.layout
        return when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_LOCATION -> {
                val drag = currentDrag ?: return true
                val cells = pages.getOrNull(vh.pageIndex) ?: return true
                trackDragMove(layout, e.x, e.y)
                val child = layout.childAt(e.x, e.y)
                if (folderTarget(child, drag, e.x, e.y) != null) {
                    setHover(child)
                    layout.previewRect = null
                } else {
                    setHover(null)
                    val target = findDropTarget(layout, cells, drag, e.x, e.y)
                    layout.previewRect = target?.let { layout.cellRect(it.first, it.second, drag.spanX, drag.spanY) }
                }
                val edge = dp(40)
                val dir = when {
                    e.x < edge -> -1
                    e.x > layout.width - edge -> 1
                    else -> 0
                }
                if (dir != edgeFlipDir) {
                    handler.removeCallbacks(edgeFlip)
                    edgeFlipDir = dir
                    if (dir != 0) handler.postDelayed(edgeFlip, 550)
                }
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                clearHover()
                layout.previewRect = null
                cancelEdgeFlip()
                true
            }
            DragEvent.ACTION_DROP -> {
                clearHover()
                layout.previewRect = null
                cancelEdgeFlip()
                dropOnPage(vh, e.x, e.y)
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                layout.previewRect = null
                true
            }
            else -> true
        }
    }

    private fun dropOnPage(vh: WorkspaceAdapter.PageVH, x: Float, y: Float): Boolean {
        val drag = currentDrag ?: return false
        if (!dragMoved) return false
        val page = vh.pageIndex
        val cells = pages.getOrNull(page) ?: return false
        val layout = vh.layout
        dismissPopup()
        val dragged = drag.item
        val child = layout.childAt(x, y)
        val target = folderTarget(child, drag, x, y)
        if (target != null && dragged is HomeItem.App) {
            when (target) {
                is HomeItem.App -> {
                    removeFromSource(drag)
                    val id = System.currentTimeMillis().toString(36)
                    folders[id] = FolderData("Folder", mutableListOf(target.key, dragged.key))
                    val idx = cells.indexOfFirst { it.key == target.key }
                    if (idx >= 0) cells[idx] = HomeCell(HomeItem.FOLDER_PREFIX + id, cells[idx].col, cells[idx].row)
                    addExtrasToFolder(id, drag.extras)
                    finishGroupDrop(drag)
                    b.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    return true
                }
                is HomeItem.Folder -> {
                    if (!folderHasRoom(target.id)) return false
                    removeFromSource(drag)
                    folders[target.id]?.apps?.add(dragged.key)
                    addExtrasToFolder(target.id, drag.extras)
                    finishGroupDrop(drag)
                    return true
                }
                else -> Unit
            }
        }
        val pos = findDropTarget(layout, cells, drag, x, y)
        if (pos == null) {
            toast("No room on this page")
            return false
        }
        removeFromSource(drag)
        cells.add(HomeCell(dragged.key, pos.first, pos.second, drag.spanX, drag.spanY))
        placeExtras(page, drag.extras)
        finishGroupDrop(drag)
        return true
    }

    private fun addExtrasToFolder(id: String, extras: List<AppInfo>) {
        if (extras.isEmpty()) return
        val f = folders[id] ?: return
        val room = (FOLDER_CAPACITY - f.apps.size).coerceAtLeast(0)
        val toAdd = extras.map { it.key }.filter { it !in f.apps }
        f.apps.addAll(toAdd.take(room))
        if (toAdd.size > room) toast("${toAdd.size - room} apps didn't fit in the folder")
    }

    private fun placeExtras(startPage: Int, extras: List<AppInfo>) {
        if (extras.isEmpty()) return
        var page = startPage
        var occ = occupancy(pages[page], null)
        for (app in extras) {
            var pos = findVacant(occ, 1, 1)
            while (pos == null) {
                page++
                if (page >= pages.size) pages.add(mutableListOf())
                occ = occupancy(pages[page], null)
                pos = findVacant(occ, 1, 1)
            }
            mark(occ, pos.first, pos.second, 1, 1)
            pages[page].add(HomeCell(app.key, pos.first, pos.second))
        }
        if (page != startPage) toast("Some apps went to page ${page + 1}")
    }

    private fun finishGroupDrop(drag: DragState) {
        drag.dropped = true
        commitLayout()
        if (drag.extras.isNotEmpty()) setSelectionMode(false)
    }

    private fun onDockDrag(e: DragEvent): Boolean {
        val adapter = dockAdapter ?: return false
        return when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> currentDrag?.item !is HomeItem.Widget
            DragEvent.ACTION_DRAG_LOCATION -> {
                val drag = currentDrag ?: return true
                trackDragMove(b.dock, e.x, e.y)
                val child = b.dock.findChildViewUnder(e.x, e.y)
                val item = child?.let { adapter.items.getOrNull(b.dock.getChildAdapterPosition(it)) }
                if (child != null && item != null && drag.item is HomeItem.App && item.key != drag.item.key && inFolderZone(child, e.x, e.y)) {
                    setHover(child)
                } else setHover(null)
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                clearHover()
                true
            }
            DragEvent.ACTION_DROP -> {
                clearHover()
                dropOnDock(adapter, e.x, e.y)
            }
            else -> true
        }
    }

    private fun folderHasRoom(id: String): Boolean {
        val n = folders[id]?.apps?.size ?: 0
        if (n >= FOLDER_CAPACITY) {
            toast("A folder holds up to $FOLDER_CAPACITY apps")
            return false
        }
        return true
    }

    private fun dropOnDock(adapter: HomeItemAdapter, x: Float, y: Float): Boolean {
        val drag = currentDrag ?: return false
        if (!dragMoved) return false
        val dragged = drag.item
        if (dragged is HomeItem.Widget) return false
        dismissPopup()
        val child = b.dock.findChildViewUnder(x, y)
        val target = child?.let { adapter.items.getOrNull(b.dock.getChildAdapterPosition(it)) }
        val sourceHere = drag.source == DragSource.Dock && dragged.key in dock

        if (child != null && target != null && target.key != dragged.key && dragged is HomeItem.App && inFolderZone(child, x, y)) {
            when (target) {
                is HomeItem.App -> {
                    removeFromSource(drag)
                    val id = System.currentTimeMillis().toString(36)
                    folders[id] = FolderData("Folder", mutableListOf(target.key, dragged.key))
                    val idx = dock.indexOf(target.key)
                    if (idx >= 0) dock[idx] = HomeItem.FOLDER_PREFIX + id
                    addExtrasToFolder(id, drag.extras)
                    finishGroupDrop(drag)
                    return true
                }
                is HomeItem.Folder -> {
                    if (!folderHasRoom(target.id)) return false
                    removeFromSource(drag)
                    folders[target.id]?.apps?.add(dragged.key)
                    addExtrasToFolder(target.id, drag.extras)
                    finishGroupDrop(drag)
                    return true
                }
                else -> Unit
            }
        }
        if (!sourceHere && dock.size >= 5) {
            toast("The dock is full")
            return false
        }
        var idx = if (child != null && target != null) {
            val pos = dock.indexOf(target.key)
            if (x < child.left + child.width / 2f) pos else pos + 1
        } else {
            (x / (b.dock.width / (dock.size + 1).toFloat())).toInt()
        }
        idx = idx.coerceIn(0, dock.size)
        if (sourceHere) {
            val from = dock.indexOf(dragged.key)
            if (from < idx) idx--
            dock.removeAt(from)
        } else {
            removeFromSource(drag)
        }
        dock.add(idx.coerceIn(0, dock.size), dragged.key)
        if (drag.extras.isNotEmpty()) {
            if (drag.extras.size + 1 > 5 - (dock.size - 1)) {
                val id = System.currentTimeMillis().toString(36)
                dock[dock.indexOf(dragged.key)] = HomeItem.FOLDER_PREFIX + id
                folders[id] = FolderData("Folder", mutableListOf(dragged.key))
                addExtrasToFolder(id, drag.extras)
                toast("Grouped into a folder to fit the dock")
            } else {
                var at = dock.indexOf(dragged.key) + 1
                for (app in drag.extras) if (app.key !in dock) dock.add(at++, app.key)
            }
        }
        finishGroupDrop(drag)
        return true
    }

    // ---------------------------------------------------------------- folders

    private fun setupFolderOverlay() {
        b.folderCard.background = GradientDrawable().apply {
            setColor(0xD9262626.toInt())
            cornerRadius = dp(28).toFloat()
        }
        b.folderOverlay.setOnClickListener { closeFolder() }
        b.folderName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                b.folderName.clearFocus()
            }
            false
        }
        b.folderGrid.layoutManager = GridLayoutManager(this, 4)
        b.folderAddBtn.setOnClickListener {
            val id = openFolderId ?: return@setOnClickListener
            val f = folders[id] ?: return@setOnClickListener
            AppPicker.show(this, apps, "Apps in ${f.name}", f.apps, FOLDER_CAPACITY) { chosen ->
                val kept = f.apps.filter { it in chosen }
                val added = chosen.filter { it !in kept }
                f.apps.clear()
                f.apps.addAll(kept + added)
                normalizeLayout()
                saveLayout()
                refreshHome()
                if (folders.containsKey(id)) openFolder(id) else closeFolder()
            }
        }
    }

    private fun pickDockApps() {
        val dockFolders = dock.filter { HomeItem.isFolderKey(it) }
        val room = (5 - dockFolders.size).coerceAtLeast(0)
        AppPicker.show(this, apps, "Apps in the dock", dock.filter { !HomeItem.isFolderKey(it) }, room) { chosen ->
            val newDock = ArrayList<String>()
            for (k in dock) if (HomeItem.isFolderKey(k) || k in chosen) newDock += k
            for (k in chosen) if (k !in newDock) newDock += k
            dock = newDock.take(5).toMutableList()
            commitLayout()
        }
    }

    private fun openFolder(id: String) {
        val f = folders[id] ?: return
        dismissPopup()
        openFolderId = id
        b.folderName.setText(f.name)
        val items = f.apps.mapNotNull { appsByKey[it] }.map { HomeItem.App(it) as HomeItem }.toMutableList()
        val rowsNeeded = ceil(items.size / 4f).toInt().coerceAtLeast(1)
        b.folderGrid.layoutParams = b.folderGrid.layoutParams.apply {
            height = minOf(rowsNeeded * dp(100), (resources.displayMetrics.heightPixels * 0.62f).toInt())
        }
        b.folderGrid.adapter = HomeItemAdapter(items, dp(100), true, ::onItemClick, { item, view ->
            showFolderAppPopup(item, view, id)
            startDrag(item, DragSource.Folder(id), view, (view.parent as? View) ?: view, 1, 1)
        }, ::badgeFor)
        b.folderOverlay.isVisible = true
        b.folderCard.alpha = 0f
        b.folderCard.scaleX = 0.9f
        b.folderCard.scaleY = 0.9f
        b.folderCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun closeFolder() {
        val id = openFolderId ?: return
        openFolderId = null
        hideKeyboard()
        b.folderName.clearFocus()
        b.folderOverlay.isVisible = false
        val f = folders[id] ?: return
        val name = b.folderName.text.toString().trim().ifEmpty { "Folder" }
        if (name != f.name) {
            f.name = name
            saveLayout()
            refreshHome()
        }
    }

    // ---------------------------------------------------------------- drawer

    private fun setupDrawer() {
        b.drawer.headerHeight = { b.toolbarRow.bottom + dp(8) }
        b.drawer.onSlide = { f ->
            b.home.alpha = 1f - f * 0.75f
            val s = 1f - f * 0.06f
            b.home.scaleX = s
            b.home.scaleY = s
        }
        b.drawer.onOpened = { updateStatusBarIcons(true) }
        b.drawer.onClosed = {
            resetDrawer()
            b.home.alpha = 1f
            b.home.scaleX = 1f
            b.home.scaleY = 1f
            updateStatusBarIcons(false)
        }
        b.drawerPager.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && drawerPages.isNotEmpty()) {
                b.drawerPager.post { b.drawerPager.adapter?.notifyDataSetChanged() }
            }
        }

        drawerState.onClick = ::launch
        drawerState.onLongClick = ::showDrawerAppPopup
        drawerState.onGroupDrag = { app, view ->
            val extras = selectedApps().filter { it.key != app.key }
            startDrag(HomeItem.App(app), DragSource.Drawer, view, (view.parent as? View) ?: view, 1, 1, extras)
        }
        drawerState.onSelectionChanged = { updateSelectionBar() }
        drawerAdapter = DrawerAdapter(drawerState)
        b.drawerPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDrawerDots(position)
        })
        val lm = GridLayoutManager(this, prefs.drawerColumns)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (drawerAdapter.isApp(position)) 1 else lm.spanCount
        }
        b.drawerList.layoutManager = lm
        b.drawerList.adapter = drawerAdapter
        b.drawerList.itemAnimator = null

        b.searchInput.doAfterTextChanged {
            query = it?.toString() ?: ""
            b.clearSearch.isVisible = query.isNotEmpty()
            refreshDrawer()
            b.drawerList.scrollToPosition(0)
        }
        b.searchInput.setOnEditorActionListener { _, _, _ ->
            val first = drawerAdapter.firstApp() ?: drawerPages.firstOrNull()?.firstOrNull()?.let { (it as? DrawerItem.App)?.app }
            when {
                first != null -> launch(first, b.searchInput)
                query.isNotBlank() -> webSearch(query)
            }
            true
        }
        b.clearSearch.setOnClickListener { b.searchInput.setText("") }
        b.sortChip.setOnClickListener { showSortMenu() }
        b.moreButton.setOnClickListener { showDrawerMenu() }

        b.selCancel.setOnClickListener { setSelectionMode(false) }
        b.selHide.setOnClickListener {
            val sel = selectedApps()
            if (sel.isEmpty()) return@setOnClickListener
            prefs.hidden = prefs.hidden + sel.map { it.key }
            setSelectionMode(false)
            refreshDrawer()
            toast("${sel.size} hidden. Manage them in Launcher settings › Hidden apps")
        }
        b.selAddHome.setOnClickListener {
            val sel = selectedApps()
            if (sel.isEmpty()) return@setOnClickListener
            var added = 0
            sel.forEach { if (addToHome(it, quiet = true)) added++ }
            setSelectionMode(false)
            toast("$added added to Home")
        }
        b.selUninstall.setOnClickListener {
            val sel = selectedApps().filter { !it.isSystem }
            if (sel.isEmpty()) {
                toast("System apps can't be uninstalled")
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Uninstall ${sel.size} apps?")
                .setMessage(sel.joinToString("\n") { "• ${it.label}" })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Uninstall") { _, _ ->
                    sel.forEach { uninstall(it) }
                    setSelectionMode(false)
                }
                .show()
        }

        setupLetterIndex()
        applyDrawerTheme()
    }

    private fun setupLetterIndex() {
        letters.forEach { c ->
            b.letterIndex.addView(TextView(this).apply {
                text = c.toString()
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
        }
        b.letterIndex.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val idx = ((e.y / v.height) * letters.size).toInt().coerceIn(0, letters.size - 1)
                    val c = letters[idx]
                    if (c != lastLetter) {
                        lastLetter = c
                        val pos = drawerAdapter.positionForLetter(c)
                        if (pos >= 0) {
                            (b.drawerList.layoutManager as GridLayoutManager).scrollToPositionWithOffset(pos, 0)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    b.letterBubble.text = c.toString()
                    b.letterBubble.isVisible = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lastLetter = null
                    b.letterBubble.isVisible = false
                    true
                }
                else -> false
            }
        }
    }

    private fun applyDrawerTheme() {
        drawerDark = when (prefs.drawerTheme) {
            "dark" -> true
            "light" -> false
            else -> isNightMode()
        }
        val bg = if (drawerDark) 0xF2141414.toInt() else 0xF7FFFFFF.toInt()
        val text = if (drawerDark) 0xFFF2F2F2.toInt() else 0xFF1A1A1A.toInt()
        val sub = if (drawerDark) 0xFF9A9A9A.toInt() else 0xFF6B6B6B.toInt()
        val field = if (drawerDark) 0x16FFFFFF else 0x0F000000
        val r = dp(28).toFloat()
        b.drawer.background = GradientDrawable().apply {
            setColor(bg)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        b.searchBox.background = GradientDrawable().apply {
            setColor(field)
            cornerRadius = dp(23).toFloat()
        }
        b.sortChip.background = GradientDrawable().apply {
            setColor(field)
            cornerRadius = dp(18).toFloat()
        }
        val subTint = ColorStateList.valueOf(sub)
        b.searchInput.setTextColor(text)
        b.searchInput.setHintTextColor(sub)
        b.searchIcon.imageTintList = subTint
        b.clearSearch.imageTintList = subTint
        b.moreButton.imageTintList = ColorStateList.valueOf(text)
        b.sortChip.setTextColor(text)
        b.sortChip.compoundDrawableTintList = ColorStateList.valueOf(text)
        b.appCount.setTextColor(sub)
        for (i in 0 until b.letterIndex.childCount) (b.letterIndex.getChildAt(i) as TextView).setTextColor(sub)
        drawerState.textColor = text
        drawerState.subColor = sub
        refreshDrawerViews()
        updateDrawerDots(b.drawerPager.currentItem)
        updateStatusBarIcons(b.drawer.isOpen)
    }

    private fun refreshDrawerViews() {
        drawerAdapter.notifyDataSetChanged()
        b.drawerPager.adapter?.notifyDataSetChanged()
    }

    private fun setSelectionMode(on: Boolean) {
        if (drawerState.selectionMode == on) return
        drawerState.selectionMode = on
        if (!on) drawerState.selected.clear()
        refreshDrawerViews()
        updateSelectionBar()
    }

    private fun toggleSelected(app: AppInfo) {
        if (!drawerState.selected.remove(app.key)) drawerState.selected.add(app.key)
        refreshDrawerViews()
        updateSelectionBar()
    }

    private fun selectedApps(): List<AppInfo> = apps.filter { it.key in drawerState.selected }

    private inner class DrawerPagerAdapter : RecyclerView.Adapter<DrawerPagerAdapter.VH>() {
        inner class VH(val rv: RecyclerView) : RecyclerView.ViewHolder(rv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val rv = RecyclerView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(dp(8), dp(4), dp(8), 0)
                layoutManager = GridLayoutManager(context, prefs.columns)
                itemAnimator = null
            }
            return VH(rv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val cell = if (b.drawerPager.height > 0) (b.drawerPager.height - dp(4)) / prefs.rows else 0
            val adapter = DrawerAdapter(drawerState, cell)
            adapter.submit(drawerPages[position])
            holder.rv.adapter = adapter
        }

        override fun getItemCount() = drawerPages.size
    }

    private fun updateDrawerDots(current: Int) {
        b.drawerDots.removeAllViews()
        if (drawerPages.size <= 1) return
        val on = drawerState.textColor
        val off = drawerState.subColor
        for (i in drawerPages.indices) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == current) on else off)
                    alpha = if (i == current) 255 else 110
                }
            }
            val size = if (i == current) dp(7) else dp(5)
            b.drawerDots.addView(tappableDot(dot, size) { b.drawerPager.setCurrentItem(i, true) })
        }
    }

    // ---------------------------------------------------------------- universal search

    private var searchOpen = false
    private var searchJob: kotlinx.coroutines.Job? = null
    private lateinit var searchAdapter: SearchAdapter
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { runSearch() }

    private fun setupSearch() {
        searchAdapter = SearchAdapter(lifecycleScope, drawerState) { row, view -> onSearchRow(row, view) }
        b.searchResults.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.searchResults.adapter = searchAdapter
        b.searchResults.itemAnimator = null
        b.globalSearch.doAfterTextChanged {
            b.globalClear.isVisible = !it.isNullOrEmpty()
            runSearch()
        }
        b.globalSearch.setOnEditorActionListener { _, _, _ ->
            val q = b.globalSearch.text.toString().trim()
            val first = searchAdapter.rows.firstOrNull { it is SearchRow.App } as? SearchRow.App
            when {
                q.isEmpty() -> Unit
                first != null && first.app.label.lowercase().startsWith(q.lowercase()) -> launch(first.app, b.globalSearch)
                else -> webSearch(q)
            }
            true
        }
        b.globalClear.setOnClickListener { b.globalSearch.setText("") }
        b.searchOverlay.setOnClickListener { closeSearch() }
        b.searchContent.setOnClickListener { }
    }

    private fun openSearch() {
        if (searchOpen) return
        dismissPopup()
        if (openFolderId != null) closeFolder()
        searchOpen = true
        applySearchTheme()
        b.globalSearch.setText("")
        searchAdapter.submit(UniversalSearch.recent(apps, prefs))
        b.searchOverlay.alpha = 0f
        b.searchOverlay.isVisible = true
        b.searchOverlay.animate().alpha(1f).setDuration(160).start()
        b.globalSearch.requestFocus()
        b.globalSearch.postDelayed({
            getSystemService(InputMethodManager::class.java).showSoftInput(b.globalSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
        updateStatusBarIcons(!drawerDark)
    }

    private fun closeSearch() {
        if (!searchOpen) return
        searchOpen = false
        searchJob?.cancel()
        hideKeyboard()
        b.globalSearch.clearFocus()
        b.searchOverlay.animate().alpha(0f).setDuration(140).withEndAction { b.searchOverlay.isVisible = false }.start()
        updateStatusBarIcons(b.drawer.isOpen)
    }

    private fun applySearchTheme() {
        val bg = if (drawerDark) 0xF0121212.toInt() else 0xF4FFFFFF.toInt()
        val field = if (drawerDark) 0x1AFFFFFF else 0x0F000000
        b.searchOverlay.setBackgroundColor(bg)
        b.globalSearchBox.background = GradientDrawable().apply {
            setColor(field)
            cornerRadius = dp(24).toFloat()
        }
        b.globalSearch.setTextColor(drawerState.textColor)
        b.globalSearch.setHintTextColor(drawerState.subColor)
        b.globalSearchIcon.imageTintList = ColorStateList.valueOf(drawerState.subColor)
        b.globalClear.imageTintList = ColorStateList.valueOf(drawerState.subColor)
    }

    private fun runSearch() {
        if (!searchOpen) return
        val q = b.globalSearch.text.toString()
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(if (q.isBlank()) 0 else 120)
            val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                UniversalSearch.search(this@LauncherActivity, q, apps, prefs, ::webSearch, ::storeSearch)
            }
            searchAdapter.submit(rows)
            b.searchResults.scrollToPosition(0)
        }
    }

    private fun onSearchRow(row: SearchRow, view: View) {
        when (row) {
            is SearchRow.App -> {
                closeSearch()
                launch(row.app, view)
            }
            is SearchRow.Setting -> {
                closeSearch()
                try {
                    startActivity(row.intent)
                } catch (e: Exception) {
                    toast("Can't open ${row.label}")
                }
            }
            is SearchRow.Contact -> {
                closeSearch()
                try {
                    val uri = android.provider.ContactsContract.Contacts.getLookupUri(row.id, row.lookupKey)
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    toast("Can't open contact")
                }
            }
            is SearchRow.Media -> {
                closeSearch()
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(row.uri, row.mime ?: "*/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                } catch (e: Exception) {
                    toast("No app can open this file")
                }
            }
            is SearchRow.Action -> {
                closeSearch()
                row.run()
            }
            is SearchRow.Permission -> permissionRequest.launch(row.permissions)
            is SearchRow.Header -> Unit
        }
    }

    private fun updateStatusBarIcons(drawerOpen: Boolean) {
        val light = drawerOpen && !drawerDark
        WindowInsetsControllerCompat(window, b.root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun refreshDrawer() {
        val items = DrawerListBuilder.build(apps, sortMode, query, prefs, ::webSearch, ::storeSearch)
        drawerState.showNewBadge = prefs.newBadge
        val paged = prefs.drawerStyle == "paged" && query.isEmpty()
        b.drawerList.isVisible = !paged
        b.drawerPager.isVisible = paged
        b.drawerDots.isVisible = paged
        if (paged) {
            val perPage = prefs.columns * prefs.rows
            drawerPages = items.filterIsInstance<DrawerItem.App>().chunked(perPage)
            val current = b.drawerPager.currentItem
            b.drawerPager.adapter = DrawerPagerAdapter()
            b.drawerPager.setCurrentItem(current.coerceIn(0, (drawerPages.size - 1).coerceAtLeast(0)), false)
            updateDrawerDots(b.drawerPager.currentItem)
            drawerAdapter.submit(emptyList())
        } else {
            drawerPages = emptyList()
            drawerAdapter.submit(items)
        }
        b.sortChip.text = sortMode.label
        val hidden = prefs.hidden
        b.appCount.text = "${apps.count { it.key !in hidden }} apps"
        val showIndex = !paged && sortMode == SortMode.NAME && query.isEmpty()
        b.letterIndex.isVisible = showIndex
        b.drawerList.updatePadding(right = if (showIndex) dp(22) else dp(8))
    }

    private fun updateSelectionBar() {
        val on = drawerState.selectionMode
        b.selectionBar.isVisible = on
        b.toolbarRow.isVisible = !on
        b.selectionCount.text = "${drawerState.selected.size} selected"
    }

    private fun resetDrawer() {
        if (b.searchInput.text.isNotEmpty()) b.searchInput.setText("")
        b.searchInput.clearFocus()
        hideKeyboard()
        setSelectionMode(false)
        b.drawerList.scrollToPosition(0)
        if (drawerPages.isNotEmpty()) b.drawerPager.setCurrentItem(0, false)
    }

    private fun openDrawer(focusSearch: Boolean = false) {
        dismissPopup()
        if (openFolderId != null) closeFolder()
        b.drawer.open()
        if (focusSearch) {
            b.searchInput.postDelayed({
                b.searchInput.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)
            }, 250)
        }
    }

    private fun closeDrawer() {
        hideKeyboard()
        b.drawer.close()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    private fun showSortMenu() {
        val pm = PopupMenu(this, b.sortChip)
        SortMode.entries.forEachIndexed { i, m ->
            pm.menu.add(0, i, i, m.label).apply {
                isCheckable = true
                isChecked = m == sortMode
            }
        }
        pm.menu.setGroupCheckable(0, true, true)
        pm.setOnMenuItemClickListener {
            sortMode = SortMode.entries[it.itemId]
            prefs.drawerSort = sortMode.name
            refreshDrawer()
            b.drawerList.scrollToPosition(0)
            true
        }
        pm.show()
    }

    private fun showDrawerMenu() {
        val pm = PopupMenu(this, b.moreButton)
        pm.menu.add(0, 0, 0, "Select apps")
        pm.menu.add(0, 1, 1, "Share app list")
        pm.menu.add(0, 2, 2, "Hidden apps")
        pm.menu.add(0, 3, 3, "Launcher settings")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> setSelectionMode(true)
                1 -> shareAppList()
                2 -> startActivity(Intent(this, HiddenAppsActivity::class.java))
                3 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        pm.show()
    }

    private fun shareAppList() {
        val rows = DrawerListBuilder.build(apps, sortMode, "", prefs, {}, {}).filterIsInstance<DrawerItem.App>()
        val text = rows.joinToString("\n") { row ->
            val extra = row.sub?.let { " ($it)" } ?: ""
            "${row.app.label} — ${row.app.packageName}$extra"
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "My apps (${rows.size}) sorted by ${sortMode.label.lowercase()}")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share app list"))
    }

    // ---------------------------------------------------------------- popups

    private fun showDrawerAppPopup(app: AppInfo, anchor: View) {
        val entries = mutableListOf<PopupEntry>()
        entries += PopupEntry("Add to Home", R.drawable.ic_home) { addToHome(app) }
        if (prefs.dockEnabled && app.key !in dock && dock.size < 5) {
            entries += PopupEntry("Add to dock", R.drawable.ic_dock) {
                dock.add(app.key)
                saveLayout()
                rebuildDock()
                toast("Added to dock")
            }
        }
        entries += PopupEntry("Hide app", R.drawable.ic_hide) {
            prefs.hidden = prefs.hidden + app.key
            refreshDrawer()
            toast("${app.label} hidden")
        }
        entries += PopupEntry("Select apps", R.drawable.ic_select) {
            setSelectionMode(true)
            toggleSelected(app)
        }
        if (hasWidgets(app.packageName)) entries += PopupEntry("Widgets", R.drawable.ic_apps) {
            closeDrawer()
            pickWidget(app.packageName)
        }
        entries += PopupEntry("App info", R.drawable.ic_info) { appInfo(app) }
        if (!app.isSystem) entries += PopupEntry("Uninstall", R.drawable.ic_delete, destructive = true) { uninstall(app) }
        dismissPopup()
        popup = AppPopup.show(anchor, app.label, entries)
        if (!drawerState.selectionMode) {
            startDrag(HomeItem.App(app), DragSource.Drawer, anchor, (anchor.parent as? View) ?: anchor, 1, 1)
        }
    }

    private fun showHomeItemPopup(item: HomeItem, anchor: View, pageIndex: Int?) {
        val entries = mutableListOf<PopupEntry>()
        val cells = if (pageIndex != null) pages.getOrNull(pageIndex) else null
        if (pageIndex != null && cells == null) return
        val removeLabel = if (pageIndex == null) "Remove from dock" else "Remove from Home"
        when (item) {
            is HomeItem.Folder -> {
                entries += PopupEntry("Rename", R.drawable.ic_settings) { openFolder(item.id) }
                entries += PopupEntry("Ungroup", R.drawable.ic_apps) {
                    val f = folders.remove(item.id)
                    if (f != null) {
                        if (cells != null) {
                            val old = cells.firstOrNull { it.key == item.key }
                            cells.removeAll { it.key == item.key }
                            val occ = occupancy(cells, null)
                            var dropped = 0
                            var first = true
                            for (k in f.apps) {
                                val p = if (first && old != null) old.col to old.row else findVacant(occ, 1, 1)
                                first = false
                                if (p == null) {
                                    dropped++
                                    continue
                                }
                                mark(occ, p.first, p.second, 1, 1)
                                cells.add(HomeCell(k, p.first, p.second))
                            }
                            if (dropped > 0) toast("$dropped apps didn't fit on this page and were left off Home")
                        } else {
                            val idx = dock.indexOf(item.key)
                            if (idx >= 0) {
                                dock.removeAt(idx)
                                val room = (5 - dock.size).coerceAtLeast(0)
                                dock.addAll(idx, f.apps.take(room))
                            }
                        }
                    }
                    commitLayout()
                }
                entries += PopupEntry(removeLabel, R.drawable.ic_close) {
                    cells?.removeAll { it.key == item.key }
                    if (cells == null) dock.remove(item.key)
                    folders.remove(item.id)
                    commitLayout()
                }
            }
            is HomeItem.App -> {
                entries += PopupEntry(removeLabel, R.drawable.ic_close) {
                    cells?.removeAll { it.key == item.key }
                    if (cells == null) dock.remove(item.key)
                    commitLayout()
                }
                if (pageIndex != null && prefs.dockEnabled && item.key !in dock && dock.size < 5) {
                    entries += PopupEntry("Add to dock", R.drawable.ic_dock) {
                        dock.add(item.key)
                        saveLayout()
                        rebuildDock()
                    }
                }
                if (hasWidgets(item.app.packageName)) entries += PopupEntry("Widgets", R.drawable.ic_apps) { pickWidget(item.app.packageName) }
                entries += PopupEntry("App info", R.drawable.ic_info) { appInfo(item.app) }
                if (!item.app.isSystem) entries += PopupEntry("Uninstall", R.drawable.ic_delete, destructive = true) { uninstall(item.app) }
            }
            is HomeItem.Widget -> return
        }
        dismissPopup()
        popup = AppPopup.show(anchor, item.label, entries)
    }

    private fun showFolderAppPopup(item: HomeItem, anchor: View, folderId: String) {
        val app = (item as? HomeItem.App)?.app ?: return
        val entries = mutableListOf<PopupEntry>()
        entries += PopupEntry("Move out to Home", R.drawable.ic_home) {
            folders[folderId]?.apps?.remove(app.key)
            normalizeLayout()
            saveLayout()
            addToHome(app, quiet = true)
            refreshHome()
        }
        if (hasWidgets(app.packageName)) entries += PopupEntry("Widgets", R.drawable.ic_apps) {
            closeFolder()
            pickWidget(app.packageName)
        }
        entries += PopupEntry("App info", R.drawable.ic_info) { appInfo(app) }
        if (!app.isSystem) entries += PopupEntry("Uninstall", R.drawable.ic_delete, destructive = true) { uninstall(app) }
        dismissPopup()
        popup = AppPopup.show(anchor, app.label, entries)
    }

    private fun dismissPopup() {
        popup?.dismiss()
        popup = null
    }

    private fun addToHome(app: AppInfo, quiet: Boolean = false): Boolean {
        if (isOnHome(app.key)) {
            if (!quiet) toast("${app.label} is already on Home")
            return false
        }
        var idx = -1
        var pos: Pair<Int, Int>? = null
        val order = listOf(b.workspace.currentItem) + pages.indices.filter { it != b.workspace.currentItem }
        for (i in order) {
            if (i !in pages.indices) continue
            pos = findVacant(occupancy(pages[i], null), 1, 1)
            if (pos != null) {
                idx = i
                break
            }
        }
        if (pos == null) {
            pages.add(mutableListOf())
            idx = pages.size - 1
            pos = 0 to 0
            workspaceAdapter?.notifyItemInserted(idx)
            b.workspace.offscreenPageLimit = max(1, pages.size)
        }
        pages[idx].add(HomeCell(app.key, pos.first, pos.second))
        saveLayout()
        workspaceAdapter?.notifyItemChanged(idx)
        updateIndicator(b.workspace.currentItem)
        if (!quiet) toast("Added to Home, page ${idx + 1}")
        return true
    }

    // ---------------------------------------------------------------- home options

    private fun showHomeOptions() {
        dismissPopup()
        val current = b.workspace.currentItem
        val items = mutableListOf("Add widget", "Apps in the dock", "Manage pages", "Change wallpaper", "Launcher settings", "All apps", "Add page", "Put all apps on Home")
        if (!LayoutPreset.isEmpty(this)) items += "Apply my Honor layout"
        if (pages.size > 1) items += "Remove this page"
        if (!isDefaultLauncher()) items += "Set as default launcher"
        MaterialAlertDialogBuilder(this)
            .setTitle("Home screen")
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Add widget" -> pickWidget()
                    "Apps in the dock" -> pickDockApps()
                    "Manage pages" -> openOverview()
                    "Change wallpaper" -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
                    "Launcher settings" -> startActivity(Intent(this, SettingsActivity::class.java))
                    "All apps" -> openDrawer()
                    "Add page" -> {
                        pages.add(mutableListOf())
                        saveLayout()
                        workspaceAdapter?.notifyItemInserted(pages.size - 1)
                        b.workspace.offscreenPageLimit = max(1, pages.size)
                        b.workspace.setCurrentItem(pages.size - 1, true)
                        updateIndicator(pages.size - 1)
                    }
                    "Put all apps on Home" -> fillHomeWithAllApps()
                    "Apply my Honor layout" -> applyPreset()
                    "Remove this page" -> removePage(current)
                    "Set as default launcher" -> requestDefaultLauncher()
                }
            }
            .show()
    }

    private fun discardWidgetsIn(pageList: List<List<HomeCell>>) {
        for (page in pageList) for (c in page) if (HomeItem.isWidgetKey(c.key)) discardWidgetId(HomeItem.widgetId(c.key))
    }

    private fun applyPreset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Apply the saved Honor layout?")
            .setMessage("Pages, folders and dock are rebuilt from the layout baked into this build. Widgets are removed, apps that aren't installed are skipped.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val r = LayoutPreset.apply(this, apps)
                discardWidgetsIn(pages)
                pages = r.pages.map { cellsOf(it) }.toMutableList()
                dock = r.dock
                folders = r.folders
                normalizeLayout()
                saveLayout()
                b.workspace.setCurrentItem(0, false)
                rebuildHome()
                if (r.unmatched.isEmpty()) toast("Layout applied") else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("${r.unmatched.size} apps not found")
                        .setMessage(r.unmatched.joinToString("\n") { "• $it" })
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    private fun fillHomeWithAllApps() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Put all apps on Home?")
            .setMessage("Every app goes onto the home pages in the order it was installed, the way Honor's standard home screen fills up. Your current pages, folders and widgets are replaced. The dock stays.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Arrange") { _, _ ->
                val hidden = prefs.hidden
                val capacity = prefs.columns * prefs.rows
                val keys = apps.filter { it.key !in hidden && it.key !in dock }
                    .sortedWith(compareBy<AppInfo> { it.installTime }.thenBy { it.label.lowercase() })
                    .map { it.key }
                discardWidgetsIn(pages)
                pages = keys.chunked(capacity).map { cellsOf(it) }.toMutableList()
                if (pages.isEmpty()) pages.add(mutableListOf())
                folders = LinkedHashMap()
                normalizeLayout()
                saveLayout()
                b.workspace.setCurrentItem(0, false)
                rebuildHome()
                toast("${keys.size} apps on ${pages.size} pages")
            }
            .show()
    }

    private fun removePage(index: Int) {
        if (pages.size <= 1 || index !in pages.indices) return
        val doRemove = {
            val removed = pages.removeAt(index)
            discardWidgetsIn(listOf(removed))
            removed.filter { HomeItem.isFolderKey(it.key) }.forEach { folders.remove(HomeItem.folderId(it.key)) }
            saveLayout()
            rebuildHome()
        }
        if (pages[index].isEmpty()) doRemove() else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove page ${index + 1}?")
                .setMessage("${pages[index].size} items on this page will be removed from Home. The apps stay installed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ -> doRemove() }
                .show()
        }
    }

    // ---------------------------------------------------------------- system helpers

    private fun isDefaultLauncher(): Boolean {
        val ri = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return ri?.activityInfo?.packageName == packageName
    }

    private fun updateDefaultBanner() {
        b.defaultBanner.isVisible = !isDefaultLauncher()
    }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        DefaultLauncher.openSettings(this)
    }

    private fun launch(app: AppInfo, view: View) {
        dismissPopup()
        val la = getSystemService(LauncherApps::class.java)
        val opts = ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.width, view.height)
        try {
            la.startMainActivity(ComponentName(app.packageName, app.activityName), Process.myUserHandle(), null, opts.toBundle())
        } catch (e: Exception) {
            toast("Can't open ${app.label}")
            return
        }
        prefs.recordLaunch(app.key)
    }

    private fun appInfo(app: AppInfo) {
        try {
            getSystemService(LauncherApps::class.java)
                .startAppDetailsActivity(ComponentName(app.packageName, app.activityName), Process.myUserHandle(), null, null)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
        }
    }

    private fun uninstall(app: AppInfo) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
        } catch (e: Exception) {
            toast("Can't uninstall ${app.label}")
        }
    }

    private fun webSearch(q: String) {
        try {
            startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")))
        }
    }

    private fun storeSearch(q: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(q)}")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(q)}&c=apps")))
        }
    }

    private fun lockScreen() {
        val svc = GestureAccessibilityService.instance
        if (svc != null) {
            if (!svc.lockScreen()) toast("Lock screen needs Android 9 or newer")
        } else {
            promptAccessibility("Double tap to lock")
        }
    }

    private fun openNotifications() {
        val svc = GestureAccessibilityService.instance
        if (svc != null && svc.openNotifications()) return
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Exception) {
            promptAccessibility("Swipe down for notifications")
        }
    }

    private fun openQuickSettings() {
        val svc = GestureAccessibilityService.instance
        if (svc != null && svc.openQuickSettings()) return
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(sb)
        } catch (e: Exception) {
            promptAccessibility("Swipe down for the control panel")
        }
    }

    // ---------------------------------------------------------------- page overview

    private fun openOverview() {
        if (overviewOpen || pages.isEmpty()) return
        dismissPopup()
        if (openFolderId != null) closeFolder()
        overviewOpen = true
        b.workspace.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(220).start()
        b.overviewList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        val adapter = OverviewAdapter()
        b.overviewList.adapter = adapter
        overviewTouchHelper.attachToRecyclerView(b.overviewList)
        b.overviewList.scrollToPosition(b.workspace.currentItem)
        b.overviewOverlay.alpha = 0f
        b.overviewOverlay.isVisible = true
        b.overviewOverlay.animate().alpha(1f).setDuration(220).start()
        b.overviewOverlay.setOnClickListener { closeOverview() }
    }

    private fun closeOverview(goTo: Int? = null) {
        if (!overviewOpen) return
        overviewOpen = false
        overviewTouchHelper.attachToRecyclerView(null)
        b.overviewOverlay.animate().alpha(0f).setDuration(180).withEndAction { b.overviewOverlay.isVisible = false }.start()
        if (goTo != null && goTo in pages.indices) b.workspace.setCurrentItem(goTo, false)
        b.workspace.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
    }

    private fun refreshOverview() {
        handler.postDelayed({ if (overviewOpen) b.overviewList.adapter?.notifyDataSetChanged() }, 120)
    }

    private fun renderPage(index: Int): android.graphics.Bitmap? {
        val layout = pageLayouts[index] ?: return null
        if (layout.width == 0 || layout.height == 0) return null
        val scale = 0.4f
        val bmp = android.graphics.Bitmap.createBitmap(
            (layout.width * scale).toInt().coerceAtLeast(1), (layout.height * scale).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val c = android.graphics.Canvas(bmp)
        c.scale(scale, scale)
        layout.draw(c)
        return bmp
    }

    private val overviewTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            if (vh.bindingAdapterPosition >= pages.size) return 0
            return makeMovementFlags(androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0)
        }

        override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            target.bindingAdapterPosition < pages.size

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val f = from.bindingAdapterPosition
            val t = to.bindingAdapterPosition
            if (f !in pages.indices || t !in pages.indices) return false
            val home = homePageIndex()
            pages.add(t, pages.removeAt(f))
            prefs.homePage = when (home) {
                f -> t
                in (minOf(f, t)..maxOf(f, t)) -> if (f < t) home - 1 else home + 1
                else -> home
            }
            rv.adapter?.notifyItemMoved(f, t)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            saveLayout()
            refreshHome()
            refreshOverview()
        }
    })

    private inner class OverviewAdapter : RecyclerView.Adapter<OverviewAdapter.VH>() {
        inner class VH(val b: com.hassan.launcher.databinding.ItemOverviewPageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = com.hassan.launcher.databinding.ItemOverviewPageBinding.inflate(layoutInflater, parent, false)
            val w = (resources.displayMetrics.widthPixels * 0.42f).toInt()
            val h = if (pageWidth > 0) (w * pageHeight / pageWidth.toFloat()).toInt() else w * 2
            vb.thumb.layoutParams = vb.thumb.layoutParams.apply { width = w; height = h }
            return VH(vb)
        }

        override fun getItemCount() = pages.size + 1

        override fun onBindViewHolder(holder: VH, position: Int) {
            val vb = holder.b
            val isAdd = position >= pages.size
            vb.buttons.isVisible = !isAdd
            vb.thumb.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (isAdd) 0x33FFFFFF else 0x22FFFFFF)
                setStroke(dp(2), if (!isAdd && position == b.workspace.currentItem) 0xFFFFFFFF.toInt() else 0x55FFFFFF)
            }
            if (isAdd) {
                vb.thumb.setImageResource(R.drawable.ic_add)
                vb.thumb.scaleType = android.widget.ImageView.ScaleType.CENTER
                vb.thumb.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                vb.pageLabel.text = "Add page"
                vb.thumb.setOnClickListener {
                    pages.add(mutableListOf())
                    saveLayout()
                    workspaceAdapter?.notifyItemInserted(pages.size - 1)
                    b.workspace.offscreenPageLimit = max(1, pages.size)
                    updateIndicator(b.workspace.currentItem)
                    notifyItemInserted(pages.size - 1)
                    refreshOverview()
                }
                vb.thumb.setOnLongClickListener(null)
                return
            }
            vb.thumb.imageTintList = null
            vb.thumb.scaleType = android.widget.ImageView.ScaleType.FIT_XY
            vb.thumb.setImageBitmap(renderPage(position))
            val isHome = position == homePageIndex()
            vb.pageLabel.text = if (isHome) "Page ${position + 1} · Home" else "Page ${position + 1}"
            vb.homeBtn.imageTintList = ColorStateList.valueOf(if (isHome) ContextCompat.getColor(this@LauncherActivity, R.color.accent) else 0xFFFFFFFF.toInt())
            vb.homeBtn.setOnClickListener {
                prefs.homePage = position
                notifyDataSetChanged()
            }
            vb.deleteBtn.isVisible = pages.size > 1
            vb.deleteBtn.setOnClickListener {
                val doRemove = {
                    val removed = pages.removeAt(position)
                    discardWidgetsIn(listOf(removed))
                    removed.filter { HomeItem.isFolderKey(it.key) }.forEach { folders.remove(HomeItem.folderId(it.key)) }
                    if (prefs.homePage >= pages.size) prefs.homePage = pages.size - 1
                    saveLayout()
                    rebuildHome()
                    notifyDataSetChanged()
                    refreshOverview()
                }
                if (pages[position].isEmpty()) doRemove() else {
                    MaterialAlertDialogBuilder(this@LauncherActivity)
                        .setTitle("Remove page ${position + 1}?")
                        .setMessage("${pages[position].size} items on this page will be removed from Home. The apps stay installed.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ -> doRemove() }
                        .show()
                }
            }
            vb.thumb.setOnClickListener { closeOverview(goTo = position) }
            vb.thumb.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                overviewTouchHelper.startDrag(holder)
                true
            }
        }
    }

    private fun promptAccessibility(feature: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(feature)
            .setMessage("This gesture needs Magic Launcher's accessibility service. Enable it under Installed apps › Magic Launcher. It doesn't read your screen.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
