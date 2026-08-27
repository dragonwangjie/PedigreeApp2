package com.example.pedigreeapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RelationshipAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val dataList = mutableListOf<Triple<Int, Int, Double>>()
    private val TYPE_ITEM = 0
    private val TYPE_LOADING = 1

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvId1: TextView = view.findViewById(R.id.tvRelId1)
        val tvId2: TextView = view.findViewById(R.id.tvRelId2)
        val tvA: TextView = view.findViewById(R.id.tvRelA)
    }

    class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int) = if (position == dataList.size) TYPE_LOADING else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_ITEM) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_relationship, parent, false)
            return ItemViewHolder(view)
        } else {
            val tv = TextView(parent.context).apply {
                text = "正在加载更多..."
                setPadding(16, 16, 16, 16)
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                gravity = android.view.Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return LoadingViewHolder(tv)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ItemViewHolder && position < dataList.size) {
            val (id1, id2, a) = dataList[position]
            holder.tvId1.text = "ID1: $id1"
            holder.tvId2.text = "ID2: $id2"
            holder.tvA.text = "A: %.4f".format(a)
        }
    }

    override fun getItemCount() = dataList.size + 1

    fun clearData() { dataList.clear(); notifyDataSetChanged() }

    fun addData(newData: List<Triple<Int, Int, Double>>) {
        val currentSize = dataList.size
        dataList.addAll(newData)
        notifyItemRangeInserted(currentSize, newData.size)
    }
    
    fun hideLoading() { if (dataList.isNotEmpty()) notifyItemRemoved(dataList.size) }
}