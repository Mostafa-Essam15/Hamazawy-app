package com.hamzawy.radio

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The "Appearance" screen: pick one of the built-in themes, or add your own picture
 * (from the TV / a USB stick, or sent from the phone) and use it as the app background.
 */
class AppearanceActivity : AppCompatActivity() {

    private lateinit var background: ImageView
    private lateinit var dim: View
    private lateinit var list: RecyclerView
    private lateinit var adapter: ThemeAdapter
    private lateinit var chips: List<TextView>

    private var accent: Int = ThemeStore.DEFAULT_ACCENT
    private var shownThemeId: String? = null
    private var phoneLink: PhoneLink? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) runImport { ThemeStore.addFromUri(this, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance)

        background = findViewById(R.id.background)
        dim = findViewById(R.id.dim)
        list = findViewById(R.id.themeList)

        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val spans = ((widthDp - 96f) / 230f).toInt().coerceIn(2, 5)
        list.layoutManager = GridLayoutManager(this, spans)

        adapter = ThemeAdapter(
            this,
            onAdd = { showAddDialog() },
            onSelect = { theme -> selectTheme(theme) },
            onDelete = { theme -> confirmDelete(theme) }
        )
        list.adapter = adapter

        chips = listOf<TextView>(
            findViewById(R.id.dimLight),
            findViewById(R.id.dimMedium),
            findViewById(R.id.dimDark)
        )
        for ((index, chip) in chips.withIndex()) {
            Ui.focusScale(chip)
            chip.setOnClickListener {
                ThemeStore.setDimLevel(this, index)
                dim.alpha = ThemeStore.dimAlpha(this)
                refreshChips()
            }
        }

        phoneLink = PhoneLink(
            this,
            allowImage = true,
            allowStation = false,
            imageHandler = { bytes -> runImport { ThemeStore.addFromBytes(this, bytes) } },
            stationHandler = { _, _ -> }
        )

        applyTheme()
        refreshList()
        focusSelected()
    }

    override fun onDestroy() {
        phoneLink?.dismiss()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- showing the current theme

    private fun applyTheme() {
        val theme = ThemeStore.selected(this)
        accent = theme.accent
        dim.alpha = ThemeStore.dimAlpha(this)
        if (shownThemeId != theme.id) {
            shownThemeId = theme.id
            ThemeStore.loadBackgroundAsync(this, theme) { bmp ->
                if (bmp != null && shownThemeId == theme.id && !isDestroyed) {
                    background.setImageBitmap(bmp)
                    background.alpha = 0f
                    background.animate().alpha(1f).setDuration(250).start()
                }
            }
        }
        refreshChips()
    }

    private fun refreshChips() {
        val level = ThemeStore.dimLevel(this)
        for ((index, chip) in chips.withIndex()) {
            chip.background = Ui.cardBackground(this, accent, 22f)
            chip.isSelected = index == level
        }
    }

    private fun refreshList() {
        adapter.submit(ThemeStore.all(this), ThemeStore.selected(this).id, accent)
    }

    private fun focusSelected() {
        val pos = adapter.positionOf(ThemeStore.selected(this).id)
        if (pos < 0) return
        list.scrollToPosition(pos)
        list.post {
            list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
    }

    // ---------------------------------------------------------------- actions

    private fun selectTheme(theme: AppTheme) {
        ThemeStore.select(this, theme.id)
        applyTheme()
        refreshList()
    }

    private fun showAddDialog() {
        val options = arrayOf<CharSequence>(
            getString(R.string.source_device),
            getString(R.string.source_phone)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_theme_title)
            .setItems(options) { _, which ->
                if (which == 0) pickFromDevice() else phoneLink?.show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickFromDevice() {
        try {
            pickImage.launch("image/*")
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(theme: AppTheme) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_theme_title)
            .setMessage(getString(R.string.delete_theme_message, theme.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                ThemeStore.delete(this, theme.id)
                applyTheme()
                refreshList()
                focusSelected()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Decodes and stores the picture off the main thread, then makes it the current theme. */
    private fun runImport(task: () -> AppTheme?) {
        Thread {
            val theme = task()
            runOnUiThread {
                if (!isDestroyed) {
                    if (theme == null) {
                        Toast.makeText(this, R.string.theme_failed, Toast.LENGTH_LONG).show()
                    } else {
                        ThemeStore.select(this, theme.id)
                        applyTheme()
                        refreshList()
                        focusSelected()
                        Toast.makeText(this, R.string.theme_added, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }
}

/** Grid of theme cards: the first card is "add theme", then the built-in and custom themes. */
private class ThemeAdapter(
    private val ctx: Context,
    private val onAdd: () -> Unit,
    private val onSelect: (AppTheme) -> Unit,
    private val onDelete: (AppTheme) -> Unit
) : RecyclerView.Adapter<ThemeAdapter.Holder>() {

    private var items: List<AppTheme?> = listOf(null)
    private var selectedId: String? = null
    private var accent: Int = ThemeStore.DEFAULT_ACCENT

    fun submit(themes: List<AppTheme>, selected: String, accent: Int) {
        val all = ArrayList<AppTheme?>()
        all.add(null)
        all.addAll(themes)
        items = all
        selectedId = selected
        this.accent = accent
        notifyDataSetChanged()
    }

    fun positionOf(themeId: String): Int = items.indexOfFirst { it != null && it.id == themeId }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_theme, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val theme = items[position]
        holder.itemView.background = Ui.cardBackground(ctx, accent)

        if (theme == null) {
            holder.boundId = null
            holder.thumb.setImageDrawable(null)
            holder.thumb.setBackgroundColor(0x33FFFFFF)
            holder.addIcon.visibility = View.VISIBLE
            holder.check.visibility = View.GONE
            holder.name.setText(R.string.add_theme)
            holder.itemView.isSelected = false
            holder.itemView.setOnClickListener { onAdd() }
            holder.itemView.setOnLongClickListener(null)
            return
        }

        holder.boundId = theme.id
        holder.addIcon.visibility = View.GONE
        holder.thumb.setBackgroundColor(Color.TRANSPARENT)
        holder.thumb.setImageDrawable(null)
        ThemeStore.loadThumbAsync(ctx, theme) { bmp ->
            if (holder.boundId == theme.id && bmp != null) holder.thumb.setImageBitmap(bmp)
        }

        val selected = theme.id == selectedId
        holder.itemView.isSelected = selected
        holder.check.visibility = if (selected) View.VISIBLE else View.GONE
        holder.check.background = Ui.circle(accent)
        holder.name.text = theme.name
        holder.itemView.setOnClickListener { onSelect(theme) }
        holder.itemView.setOnLongClickListener {
            if (!theme.isBuiltin) {
                onDelete(theme)
                true
            } else {
                false
            }
        }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val addIcon: ImageView = v.findViewById(R.id.addIcon)
        val check: ImageView = v.findViewById(R.id.check)
        val name: TextView = v.findViewById(R.id.themeName)
        var boundId: String? = null

        init {
            Ui.focusScale(v)
        }
    }
}
