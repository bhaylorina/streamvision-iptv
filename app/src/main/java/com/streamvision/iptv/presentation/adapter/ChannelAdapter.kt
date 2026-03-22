package com.streamvision.iptv.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.ItemChannelBinding
import com.streamvision.iptv.domain.model.Channel

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelClick(getItem(position))
                }
            }
            binding.btnFavorite.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onFavoriteClick(getItem(position))
                }
            }
        }

        fun bind(channel: Channel) {
            binding.apply {
                tvChannelName.text = channel.name
                tvGroup.text = channel.group ?: "Ungrouped"
                
                if (!channel.logo.isNullOrEmpty()) {
                    ivChannelLogo.load(channel.logo) {
                        crossfade(true)
                        placeholder(R.drawable.ic_channel_placeholder)
                        error(R.drawable.ic_channel_placeholder)
                        transformations(CircleCropTransformation())
                    }
                } else {
                    ivChannelLogo.setImageResource(R.drawable.ic_channel_placeholder)
                }

                btnFavorite.setImageResource(
                    if (channel.isFavorite) R.drawable.ic_favorite_filled
                    else R.drawable.ic_favorite_outline
                )
            }
        }
    }

    class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}
