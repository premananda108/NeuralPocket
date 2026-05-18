package ua.pp.prema.NeuralPocket.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ua.pp.prema.NeuralPocket.R
import ua.pp.prema.NeuralPocket.data.Chat

class ChatListAdapter(
    private val chats: MutableList<Chat>,
    private val onChatClick: (Int) -> Unit,
    private val onChatLongClick: (Int) -> Unit
) : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {

    private var activeChatIndex: Int = 0

    fun setActiveIndex(index: Int) {
        val old = activeChatIndex
        activeChatIndex = index
        notifyItemChanged(old)
        notifyItemChanged(index)
    }

    fun updateChats(newChats: List<Chat>) {
        chats.clear()
        chats.addAll(newChats)
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView   = view.findViewById(R.id.chatTitle)
        val preview: TextView = view.findViewById(R.id.chatPreview)
        val avatar: TextView  = view.findViewById(R.id.chatAvatar)
        val root: View        = view

        fun bind(chat: Chat, index: Int, isActive: Boolean) {
            title.text = chat.title
            val lastMsg = chat.messages.lastOrNull { !it.isStreaming }
            preview.text = lastMsg?.text?.take(50) ?: "No messages"
            avatar.text = chat.title.take(1).uppercase()

            root.isSelected = isActive
            root.setBackgroundResource(
                if (isActive) R.drawable.bg_chat_item_active else R.drawable.bg_chat_item
            )

            root.setOnClickListener { onChatClick(index) }
            root.setOnLongClickListener { onChatLongClick(index); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(chats[position], position, position == activeChatIndex)

    override fun getItemCount() = chats.size
}
