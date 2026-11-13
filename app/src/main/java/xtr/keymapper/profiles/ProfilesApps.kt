package xtr.keymapper.profiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xtr.keymapper.R
import xtr.keymapper.databinding.AppViewBinding
import xtr.keymapper.databinding.FragmentProfilesAppsBinding

class ProfilesApps private constructor(context: Context) {
    lateinit var binding: FragmentProfilesAppsBinding
    val appsView: View = createView(LayoutInflater.from(context))

    private var mListener: ProfileSelector.OnAppSelectedListener? = null

    fun interface OnAppsLoadedListener {
        fun onAppsLoaded(profilesApps: ProfilesApps, adapter: AppsGridAdapter, dialog: AlertDialog)
    }

    @UiThread
    private fun asyncLoadApps(onAppsLoadedListener: OnAppsLoadedListener, dialog: AlertDialog) {
        val context = appsView.context

        Thread {
            val adapter = AppsGridAdapter(context)
            val mHandler = Handler(Looper.getMainLooper())
            mHandler.post { onAppsLoadedListener.onAppsLoaded(this, adapter, dialog) }
        }.start()
    }

    fun setListener(mListener: ProfileSelector.OnAppSelectedListener) {
        this.mListener = mListener
    }

    private fun createView(inflater: LayoutInflater): View {
        binding = FragmentProfilesAppsBinding.inflate(inflater)
        return binding.root
    }

    fun onDestroyView() {
    }

    inner class AppsGridAdapter(context: Context) : RecyclerView.Adapter<RecyclerViewHolder>() {

        private val appsDataArrayList = ArrayList<RecyclerData>()

        init {
            val pm = context.packageManager
            val i = Intent(Intent.ACTION_MAIN, null)
            i.addCategory(Intent.CATEGORY_LAUNCHER)

            val allApps = pm.queryIntentActivities(i, 0)

            for (ri in allApps)
                appsDataArrayList.add(
                    RecyclerData(
                        ri.activityInfo.packageName,
                        ri.loadLabel(pm),
                        ri.activityInfo.loadIcon(pm)
                    )
                )
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerViewHolder {
            val itemBinding =
                AppViewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return RecyclerViewHolder(itemBinding) { position ->
                mListener!!.onAppSelected(appsDataArrayList[position].packageName)
            }
        }

        override fun onBindViewHolder(holder: RecyclerViewHolder, position: Int) {
            val recyclerData = appsDataArrayList[position]
            holder.binding.appName.text = recyclerData.title
            holder.binding.appIcon.setImageDrawable(recyclerData.icon)
        }

        override fun getItemCount(): Int {
            return appsDataArrayList.size
        }
    }

    private class RecyclerData(
        val packageName: String,
        val title: CharSequence,
        val icon: Drawable
    )

    class RecyclerViewHolder(
        val binding: AppViewBinding,
        private val onItemClicked: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {

        init {
            binding.root.setOnClickListener(this)
        }

        override fun onClick(view: View) {
            onItemClicked(bindingAdapterPosition)
        }
    }

    companion object {
        @UiThread
        fun asyncLoadAppsAndThen(
            context: Context,
            builder: MaterialAlertDialogBuilder,
            onAppsLoadedListener: OnAppsLoadedListener
        ) {
            builder.setView(R.layout.loading)
            val dialog = ProfileSelector.showDialog(builder)
            val mProfilesApps = ProfilesApps(context)
            mProfilesApps.asyncLoadApps(onAppsLoadedListener, dialog)
        }
    }
}
