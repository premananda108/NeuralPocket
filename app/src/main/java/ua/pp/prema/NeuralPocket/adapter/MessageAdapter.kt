package ua.pp.prema.NeuralPocket.adapter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ua.pp.prema.NeuralPocket.R
import ua.pp.prema.NeuralPocket.data.ChatMessage
import io.noties.markwon.Markwon
import java.util.Date

class MessageAdapter(
    private val messages: MutableList<ChatMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_AI   = 2
    }

    private var markwon: Markwon? = null

    // ── User bubble ──────────────────────────────────────────────────────────

    inner class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val messageText: TextView   = view.findViewById(R.id.messageText)
        private val timeText: TextView      = view.findViewById(R.id.timeText)
        private val imagePreview: ImageView = view.findViewById(R.id.imagePreview)
        private val audioChip: View         = view.findViewById(R.id.audioChip)

        fun bind(msg: ChatMessage) {
            val ctx = messageText.context
            val tv = TypedValue()
            ctx.theme.resolveAttribute(R.attr.colorUserBubbleText, tv, true)
            messageText.setTextColor(tv.data)
            timeText.text = formatTime(msg.timestamp)

            if (msg.imageUriString != null) {
                imagePreview.visibility = View.VISIBLE
                imagePreview.setImageURI(Uri.parse(msg.imageUriString))
            } else {
                imagePreview.visibility = View.GONE
            }

            audioChip.visibility = if (msg.hasAudio) View.VISIBLE else View.GONE
            
            val isDefaultAudio = msg.hasAudio && (msg.text == ctx.getString(R.string.audio_message) || msg.text.isEmpty())
            val isDefaultImage = msg.imageUriString != null && (msg.text == ctx.getString(R.string.image_message) || msg.text.isEmpty())
            
            if (isDefaultAudio || isDefaultImage) {
                messageText.visibility = View.GONE
            } else {
                messageText.visibility = View.VISIBLE
                messageText.text = msg.text
            }
        }
    }

    // ── AI bubble ────────────────────────────────────────────────────────────

    inner class AiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val messageText: TextView = view.findViewById(R.id.messageText)
        private val timeText: TextView    = view.findViewById(R.id.timeText)
        private val typingDots: TextView  = view.findViewById(R.id.typingDots)

        private var pulseAnimator: ObjectAnimator? = null

        fun bind(msg: ChatMessage) {
            val tv = TypedValue()
            messageText.context.theme.resolveAttribute(R.attr.colorAiBubbleText, tv, true)
            messageText.setTextColor(tv.data)
            markwon?.setMarkdown(messageText, msg.text) ?: run { messageText.text = msg.text }
            if (msg.isStreaming) {
                typingDots.visibility = View.VISIBLE
                if (pulseAnimator == null) {
                    pulseAnimator = ObjectAnimator.ofFloat(typingDots, "alpha", 0.3f, 1.0f).apply {
                        duration = 500
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                }
            } else {
                cancelAnimator()
            }
            timeText.text = if (msg.isStreaming) "" else formatTime(msg.timestamp)
        }

        fun cancelAnimator() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            typingDots.visibility = View.GONE
            typingDots.alpha = 1.0f
        }
    }

    // ── Adapter overrides ────────────────────────────────────────────────────

    override fun getItemViewType(position: Int) =
        if (messages[position].isUser) TYPE_USER else TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (markwon == null) {
            markwon = Markwon.create(parent.context)
        }
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserViewHolder(inflater.inflate(R.layout.item_message_user, parent, false))
        } else {
            AiViewHolder(inflater.inflate(R.layout.item_message_ai, parent, false))
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is AiViewHolder) holder.cancelAnimator()
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserViewHolder -> holder.bind(messages[position])
            is AiViewHolder   -> holder.bind(messages[position])
        }
    }

    override fun getItemCount() = messages.size

    // ── Public helpers ────────────────────────────────────────────────────────

    fun addMessage(msg: ChatMessage): Int {
        messages.add(msg)
        val idx = messages.size - 1
        notifyItemInserted(idx)
        return idx
    }

    fun updateMessageAt(index: Int, newText: String, streaming: Boolean = false) {
        if (index < 0 || index >= messages.size) return
        messages[index] = messages[index].copy(text = newText, isStreaming = streaming)
        notifyItemChanged(index)
    }

    fun replaceAll(newMessages: List<ChatMessage>) {
        val old = messages.toList()
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newMessages.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                old[oldPos].id == newMessages[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                old[oldPos] == newMessages[newPos]
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        messages.clear()
        messages.addAll(newMessages)
        diffResult.dispatchUpdatesTo(this)
    }

    private fun formatTime(ts: Long): String =
        DateFormat.format("HH:mm", Date(ts)).toString()
}
