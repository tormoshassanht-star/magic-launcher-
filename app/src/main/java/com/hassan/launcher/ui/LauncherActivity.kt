package com.hassan.launcher.ui

import android.app.ActivityOptions
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
    }

    private lateinit var b: ActivityLauncherBinding
    private lateinit var prefs: Prefs
    private lateinit var behavior: BottomSheetBehavior<View>
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var host: AppWidgetHost
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
        override fun onSwipeUp() {
            if (currentDrag == null) openDrawer()
        }

        override fun onSwipeDown() {
            if (currentDrag == null && prefs.swipeDownNotifications) openNotifications()
        }

        override fun onDoubleTapEmpty() {
            if (prefs.doubleTapLock) lockScreen()
        }

        override fun onLongPressEmpty() {
            b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showHomeOptions()
        }
    }

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
        host = AppWidgetHost(this, HOST_ID)
        sortMode = SortMode.of(prefs.drawerSort)
        sig = prefs.configSignature()

        setupInsets()
        setupDrawer()
        setupHomeChrome()
        setupDragTargets()
        setupFolderOverlay()
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

        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            host.startListening()
        } catch (e: Exception) {
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(batteryReceiver)
        try {
            host.stopListening()
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
            openFolderId != null -> closeFolder()
            behavior.state != BottomSheetBehavior.STATE_HIDDEN -> closeDrawer()
            else -> b.workspace.setCurrentItem(0, true)
        }
    }

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
        if (openFolderId != null) {
            closeFolder()
            return
        }
        if (behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            when {
                drawerAdapter.selectionMode -> drawerAdapter.setSelectionMode(false)
                query.isNotEmpty() -> b.searchInput.setText("")
                else -> closeDrawer()
            }
        } else {
            b.workspace.setCurrentItem(0, true)
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
            insets
        }
    }

    private fun setupHomeChrome() {
        val rootGestures = HomeGestures(this, gestureHost) { true }
        b.home.setOnTouchListener { _, e ->
            rootGestures.detector.onTouchEvent(e)
            true
        }
        b.searchBar.setOnClickListener { openDrawer(focusSearch = true) }
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
        val current = b.workspace.currentItem
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
        val da = HomeItemAdapter(items, dp(84), false, ::onItemClick) { item, view ->
            showHomeItemPopup(item, view, null)
            startDrag(item, DragSource.Dock, view, (view.parent as? View) ?: view, 1, 1)
        }
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
            b.indicator.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
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

    private fun pickWidget() {
        if (pageWidth == 0) return
        WidgetPicker.show(this, cellWpx(), cellHpx()) { addWidget(it) }
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
        entries += PopupEntry("Resize", R.drawable.ic_apps) { showResizeDialog(item, pageIndex) }
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

    private fun showResizeDialog(item: HomeItem.Widget, pageIndex: Int) {
        val cells = pages.getOrNull(pageIndex) ?: return
        val cell = cells.firstOrNull { it.key == item.key } ?: return
        val cols = prefs.columns
        val rows = prefs.rows
        val occ = occupancy(cells, item.key)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        for (r in 0 until rows) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val sx = c + 1
                val sy = r + 1
                val ok = fits(occ, cell.col, cell.row, sx, sy)
                val selected = sx <= cell.spanX && sy <= cell.spanY
                val v = View(this).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(
                            when {
                                selected -> ContextCompat.getColor(this@LauncherActivity, R.color.accent)
                                ok -> 0x33888888
                                else -> 0x11888888
                            },
                        )
                    }
                    isEnabled = ok
                    if (ok) setOnClickListener {
                        cell.spanX = sx
                        cell.spanY = sy
                        saveLayout()
                        refreshHome()
                        dialog?.dismiss()
                    }
                }
                rowView.addView(v, LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            grid.addView(rowView)
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Resize ${item.label}")
            .setMessage("Tap the bottom-right corner of the size you want. Greyed cells don't fit next to the other items.")
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .show()
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
                    if (trackDragMove(v, e.x, e.y) && !drawerClosedForDrag && behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
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
            dragMoveView = v
            dragMoveX = x
            dragMoveY = y
            return false
        }
        val moved = abs(x - dragMoveX) > dp(28) || abs(y - dragMoveY) > dp(28)
        if (moved) dismissPopup()
        return moved
    }

    private fun startDrag(item: HomeItem, source: DragSource, shadowView: View, ghost: View, spanX: Int, spanY: Int) {
        if (currentDrag != null) return
        val state = DragState(item, source, ghost, spanX, spanY)
        val scale = if (item is HomeItem.Widget) 1.04f else 1.18f
        val started = try {
            shadowView.startDragAndDrop(ClipData.newPlainText("item", item.key), LiftShadow(shadowView, scale), state, 0)
        } catch (e: Exception) {
            false
        }
        if (!started) return
        currentDrag = state
        dragMoveView = null
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
                    drag.dropped = true
                    commitLayout()
                    b.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    return true
                }
                is HomeItem.Folder -> {
                    removeFromSource(drag)
                    folders[target.id]?.apps?.add(dragged.key)
                    drag.dropped = true
                    commitLayout()
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
        drag.dropped = true
        commitLayout()
        return true
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

    private fun dropOnDock(adapter: HomeItemAdapter, x: Float, y: Float): Boolean {
        val drag = currentDrag ?: return false
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
                    drag.dropped = true
                    commitLayout()
                    return true
                }
                is HomeItem.Folder -> {
                    removeFromSource(drag)
                    folders[target.id]?.apps?.add(dragged.key)
                    drag.dropped = true
                    commitLayout()
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
        drag.dropped = true
        commitLayout()
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
    }

    private fun openFolder(id: String) {
        val f = folders[id] ?: return
        dismissPopup()
        openFolderId = id
        b.folderName.setText(f.name)
        val items = f.apps.mapNotNull { appsByKey[it] }.map { HomeItem.App(it) as HomeItem }.toMutableList()
        b.folderGrid.adapter = HomeItemAdapter(items, dp(100), true, ::onItemClick) { item, view ->
            showFolderAppPopup(item, view, id)
            startDrag(item, DragSource.Folder(id), view, (view.parent as? View) ?: view, 1, 1)
        }
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
        behavior = BottomSheetBehavior.from(b.drawer)
        behavior.isHideable = true
        behavior.skipCollapsed = true
        behavior.peekHeight = 0
        behavior.isFitToContents = true
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        resetDrawer()
                        b.home.alpha = 1f
                        b.home.scaleX = 1f
                        b.home.scaleY = 1f
                        updateStatusBarIcons(false)
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> updateStatusBarIcons(true)
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val visible = (1f - bottomSheet.top / b.root.height.toFloat()).coerceIn(0f, 1f)
                b.home.alpha = 1f - visible * 0.75f
                val s = 1f - visible * 0.06f
                b.home.scaleX = s
                b.home.scaleY = s
            }
        })

        drawerAdapter = DrawerAdapter(::launch, ::showDrawerAppPopup) { updateSelectionBar() }
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
            val first = drawerAdapter.firstApp()
            when {
                first != null -> launch(first, b.searchInput)
                query.isNotBlank() -> webSearch(query)
            }
            true
        }
        b.clearSearch.setOnClickListener { b.searchInput.setText("") }
        b.sortChip.setOnClickListener { showSortMenu() }
        b.moreButton.setOnClickListener { showDrawerMenu() }

        b.selCancel.setOnClickListener { drawerAdapter.setSelectionMode(false) }
        b.selHide.setOnClickListener {
            val sel = drawerAdapter.selectedApps()
            if (sel.isEmpty()) return@setOnClickListener
            prefs.hidden = prefs.hidden + sel.map { it.key }
            drawerAdapter.setSelectionMode(false)
            refreshDrawer()
            toast("${sel.size} hidden. Manage them in Launcher settings › Hidden apps")
        }
        b.selAddHome.setOnClickListener {
            val sel = drawerAdapter.selectedApps()
            if (sel.isEmpty()) return@setOnClickListener
            var added = 0
            sel.forEach { if (addToHome(it, quiet = true)) added++ }
            drawerAdapter.setSelectionMode(false)
            toast("$added added to Home")
        }
        b.selUninstall.setOnClickListener {
            val sel = drawerAdapter.selectedApps().filter { !it.isSystem }
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
                    drawerAdapter.setSelectionMode(false)
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
        drawerAdapter.textColor = text
        drawerAdapter.subColor = sub
        drawerAdapter.notifyDataSetChanged()
        updateStatusBarIcons(behavior.state == BottomSheetBehavior.STATE_EXPANDED)
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
        drawerAdapter.showNewBadge = prefs.newBadge
        drawerAdapter.submit(items)
        b.sortChip.text = sortMode.label
        val hidden = prefs.hidden
        b.appCount.text = "${apps.count { it.key !in hidden }} apps"
        val showIndex = sortMode == SortMode.NAME && query.isEmpty()
        b.letterIndex.isVisible = showIndex
        b.drawerList.updatePadding(right = if (showIndex) dp(22) else dp(8))
    }

    private fun updateSelectionBar() {
        val on = drawerAdapter.selectionMode
        b.selectionBar.isVisible = on
        b.toolbarRow.isVisible = !on
        b.selectionCount.text = "${drawerAdapter.selected.size} selected"
    }

    private fun resetDrawer() {
        if (b.searchInput.text.isNotEmpty()) b.searchInput.setText("")
        b.searchInput.clearFocus()
        hideKeyboard()
        drawerAdapter.setSelectionMode(false)
        b.drawerList.scrollToPosition(0)
    }

    private fun openDrawer(focusSearch: Boolean = false) {
        dismissPopup()
        if (openFolderId != null) closeFolder()
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        if (focusSearch) {
            b.searchInput.postDelayed({
                b.searchInput.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)
            }, 250)
        }
    }

    private fun closeDrawer() {
        hideKeyboard()
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
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
                0 -> drawerAdapter.setSelectionMode(true)
                1 -> shareAppList()
                2 -> startActivity(Intent(this, HiddenAppsActivity::class.java))
                3 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        pm.show()
    }

    private fun shareAppList() {
        val rows = drawerAdapter.items.filterIsInstance<DrawerItem.App>()
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
            drawerAdapter.setSelectionMode(true)
            drawerAdapter.toggle(app)
        }
        entries += PopupEntry("App info", R.drawable.ic_info) { appInfo(app) }
        if (!app.isSystem) entries += PopupEntry("Uninstall", R.drawable.ic_delete, destructive = true) { uninstall(app) }
        dismissPopup()
        popup = AppPopup.show(anchor, app.label, entries)
        if (!drawerAdapter.selectionMode) {
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
        for (i in pages.indices) {
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
        val items = mutableListOf("Add widget", "Change wallpaper", "Launcher settings", "All apps", "Add page", "Put all apps on Home")
        if (!LayoutPreset.isEmpty(this)) items += "Apply my Honor layout"
        if (pages.size > 1) items += "Remove this page"
        if (!isDefaultLauncher()) items += "Set as default launcher"
        MaterialAlertDialogBuilder(this)
            .setTitle("Home screen")
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Add widget" -> pickWidget()
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
