package com.example.pedigreeapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class InbreedingAdapter(private var dataList: List<Pair<Int, Double>>) :
    RecyclerView.Adapter<InbreedingAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvId: TextView = view.findViewById(R.id.tvInfId)
        val tvF: TextView = view.findViewById(R.id.tvInfF)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_inbreeding, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (id, f) = dataList[position]
        holder.tvId.text = "ID: $id"
        holder.tvF.text = "F: %.4f".format(f)
    }

    override fun getItemCount() = dataList.size

    fun updateData(newData: List<Pair<Int, Double>>) {
        dataList = newData
        notifyDataSetChanged()
    }
}