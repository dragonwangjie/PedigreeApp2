package com.example.pedigreeapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PedigreeAdapter(private var dataList: List<PedigreeRecord>) :
    RecyclerView.Adapter<PedigreeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvId: TextView = view.findViewById(R.id.tvId)
        val tvSire: TextView = view.findViewById(R.id.tvSire)
        val tvDam: TextView = view.findViewById(R.id.tvDam)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pedigree, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataList[position]
        holder.tvId.text = "ID: ${item.id}"
        holder.tvSire.text = "父亲: ${if(item.sireId == 0) "未知" else item.sireId}"
        holder.tvDam.text = "母亲: ${if(item.damId == 0) "未知" else item.damId}"
    }

    override fun getItemCount() = dataList.size

    fun updateData(newData: List<PedigreeRecord>) {
        dataList = newData
        notifyDataSetChanged()
    }
}