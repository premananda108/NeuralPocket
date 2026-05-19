package ua.pp.prema.NeuralPocket.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import ua.pp.prema.NeuralPocket.R
import ua.pp.prema.NeuralPocket.data.Chat

class ChatListAdapter(
    private val chats: MutableList<Chat>,
    private val onChatClick: (Int) -> Unit,
    private val onChatLongClick: (Int) -> Unit
) : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {

    private var activeChatIndex: Int = 0

    fun setActiveIndex(index: Int) {
        if (activeChatIndex == index) return
        val old = activeChatIndex
        activeChatIndex = index
        if (old in 0 until chats.size) notifyItemChanged(old)
        if (index in 0 until chats.size) notifyItemChanged(index)
    }

    fun updateChats(newChats: List<Chat>) {
        val old = chats.toList()
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newChats.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                old[oldPos].id == newChats[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val o = old[oldPos]; val n = newChats[newPos]
                return o.title == n.title && o.messages.lastOrNull()?.text == n.messages.lastOrNull()?.text
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        chats.clear()
        chats.addAll(newChats)
        diffResult.dispatchUpdatesTo(this)
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

            root.setOnClickListener { 
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onChatClick(pos) 
            }
            root.setOnLongClickListener { 
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onChatLongClick(pos)
                true 
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(chats[position], position, position == activeChatIndex)

    override fun getItemCount() = chats.size
}
