package com.mthtamim.callshield.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.mthtamim.callshield.R

/** Simple two-line list adapter used by the Lists and Log screens. */
class RowAdapter(
    context: Context,
    private val rows: List<Pair<String, String>>
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Pair<String, String> = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_row, parent, false)
        val title = view.findViewById<TextView>(R.id.textTitle)
        val sub = view.findViewById<TextView>(R.id.textSub)
        title.text = rows[position].first
        sub.text = rows[position].second
        return view
    }
}
