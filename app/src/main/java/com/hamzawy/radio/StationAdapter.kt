package com.hamzawy.radio

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Station tiles grouped under category headers. */
class StationAdapter(
    private val ctx: Context,
    private val onClick: (Station) -> Unit,
    private val onLongClick: (Station) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        class Header(val title: String) : Row()
        class Item(val station: Station) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var playingId: String? = null
    private var accent: Int = ThemeStore.DEFAULT_ACCENT

    fun isHeader(position: Int): Boolean = rows[position] is Row.Header

    fun positionOf(stationId: String?): Int {
        if (stationId == null) return -1
        return rows.indexOfFirst { it is Row.Item && it.station.id == stationId }
    }

    fun firstItemPosition(): Int = rows.indexOfFirst { it is Row.Item }

    fun submit(stations: List<Station>) {
        val out = ArrayList<Row>()
        var lastCategory: String? = null
        for (s in stations) {
            if (s.category != lastCategory) {
                lastCategory = s.category
                out.add(Row.Header(Categories.title(ctx, s.category)))
            }
            out.add(Row.Item(s))
        }
        rows = out
        notifyDataSetChanged()
    }

    fun setAccent(color: Int) {
        if (color == accent) return
        accent = color
        notifyDataSetChanged()
    }

    /** Marks the station that is currently loaded in the player. */
    fun setPlaying(id: String?) {
        if (id == playingId) return
        val old = positionOf(playingId)
        val new = positionOf(id)
        playingId = id
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_header, parent, false) as TextView)
        } else {
            ItemHolder(inflater.inflate(R.layout.item_station, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (row is Row.Header && holder is HeaderHolder) {
            holder.title.text = row.title
            holder.title.setTextColor(accent)
        } else if (row is Row.Item && holder is ItemHolder) {
            bindItem(holder, row.station)
        }
    }

    private fun bindItem(h: ItemHolder, s: Station) {
        val playing = s.id == playingId
        h.name.text = s.name
        h.subtitle.text = s.subtitle
        h.badge.text = if (s.badge.isNotEmpty()) s.badge else s.name.take(1)
        h.badge.background = Ui.circle(Ui.monogramColor(s.id))
        h.card.background = Ui.cardBackground(ctx, accent)
        h.card.isSelected = playing
        h.playing.visibility = if (playing) View.VISIBLE else View.GONE
        h.card.setOnClickListener { onClick(s) }
        h.card.setOnLongClickListener {
            onLongClick(s)
            true
        }
    }

    private class HeaderHolder(val title: TextView) : RecyclerView.ViewHolder(title)

    private class ItemHolder(v: View) : RecyclerView.ViewHolder(v) {
        val card: View = v
        val badge: TextView = v.findViewById(R.id.badge)
        val name: TextView = v.findViewById(R.id.name)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val playing: ImageView = v.findViewById(R.id.playingIcon)

        init {
            Ui.focusScale(v)
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}
