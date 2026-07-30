package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.kiduyuk.klausk.kiduyutv.R
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model.StreamItem

class StreamSelectionDialog(
    context: Context,
    private var streams: List<StreamItem>,
    private var activeUrl: String?,
    private val onStreamSelected: (StreamItem) -> Unit
) : Dialog(context) {

    private val list: ListView
    private val close: TextView
    private val streamAdapter: StreamAdapter

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_direct_stream_sources, null)
        setContentView(view)

        window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.setDimAmount(0.6f)
            it.setGravity(Gravity.CENTER)
        }

        list = view.findViewById(R.id.listStreams)
        close = view.findViewById(R.id.btnCloseStreams)
        streamAdapter = StreamAdapter(context, streams, activeUrl)
        list.adapter = streamAdapter
        list.setOnItemClickListener { _, _, position, _ ->
            streams.getOrNull(position)?.let {
                onStreamSelected(it)
                dismiss()
            }
        }
        close.setOnClickListener { dismiss() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCanceledOnTouchOutside(true)
    }

    override fun onStart() {
        super.onStart()
        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.9f).toInt()
        val preferredWidth = (680 * context.resources.displayMetrics.density).toInt()
        window?.setLayout(
            preferredWidth.coerceAtMost(maxWidth),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        val activeIndex = streams.indexOfFirst { it.url == activeUrl }.coerceAtLeast(0)
        list.setSelection(activeIndex)
        list.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        dismiss()
    }

    /**
     * Refresh the dialog with an updated stream list. Called by
     * [com.kiduyuk.klausk.kiduyutv.ui.player.directstream.DirectStreamActivity]
     * once the [StreamValidator] finishes probing each entry, so the
     * "stream ok" badge appears without the user having to reopen the
     * dialog.
     */
    fun updateStreams(updated: List<StreamItem>) {
        if (!isShowing) return
        streams = updated
        streamAdapter.replace(updated, activeUrl)
    }

    private class StreamAdapter(
        private val context: Context,
        private var streams: List<StreamItem>,
        private var activeUrl: String?
    ) : BaseAdapter() {
        override fun getCount(): Int = streams.size
        override fun getItem(position: Int): StreamItem = streams[position]
        override fun getItemId(position: Int): Long = position.toLong()

        fun replace(newStreams: List<StreamItem>, newActiveUrl: String?) {
            streams = newStreams
            activeUrl = newActiveUrl
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val stream = getItem(position)
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_direct_stream_track, parent, false)
            val host = runCatching { Uri.parse(stream.url).host }.getOrNull().orEmpty()
            val title = stream.title.ifBlank { stream.name.ifBlank { "Stream" } }
            view.findViewById<TextView>(R.id.trackTitle).text =
                "${position + 1}. $title"
            view.findViewById<TextView>(R.id.trackSubtitle).apply {
                text = listOf(stream.quality, host).filter { it.isNotBlank() }.joinToString(" • ")
                visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            val active = stream.url == activeUrl
            view.findViewById<View>(R.id.trackCheck).visibility =
                if (active) View.VISIBLE else View.INVISIBLE
            view.isActivated = active

            // Render the validation status badge. We only show "stream ok"
            // when the upstream probe reported 2xx; otherwise the row stays
            // neutral and the user can still try it manually.
            val statusView = view.findViewById<TextView>(R.id.trackStatus)
            when {
                stream.isValid -> {
                    statusView.text = context.getString(R.string.stream_ok)
                    statusView.setBackgroundResource(R.drawable.bg_stream_status_ok)
                    statusView.visibility = View.VISIBLE
                }
                stream.isChecking -> {
                    statusView.text = context.getString(R.string.stream_checking)
                    statusView.setBackgroundResource(R.drawable.bg_stream_status_pending)
                    statusView.visibility = View.VISIBLE
                }
                else -> {
                    statusView.visibility = View.GONE
                }
            }
            return view
        }
    }
}