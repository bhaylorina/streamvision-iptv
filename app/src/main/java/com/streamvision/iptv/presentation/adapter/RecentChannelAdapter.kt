package com.streamvision.iptv.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.ItemChannelRecentBinding
import com.streamvision.iptv.domain.model.Channel

class RecentChannelAdapter(
    private val onChannelClick: (Channel) -> Unit
) : ListAdapter<Channel, RecentChannelAdapter.ViewHolder>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelRecentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(
        private val binding: ItemChannelRecentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onChannelClick(getItem(pos))
            }
        }

        fun bind(channel: Channel) {
            binding.tvRecentName.text = channel.name
            if (!channel.logo.isNullOrEmpty()) {
                binding.ivRecentLogo.load(channel.logo) {
                    crossfade(true)
                    placeholder(R.drawable.ic_channel_placeholder)
                    error(R.drawable.ic_channel_placeholder)
                    transformations(CircleCropTransformation())
                }
            } else {
                binding.ivRecentLogo.setImageResource(R.drawable.ic_channel_placeholder)
            }
        }
    }

    class Diff : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(a: Channel, b: Channel) = a.id == b.id
        override fun areContentsTheSame(a: Channel, b: Channel) = a == b
    }
}
