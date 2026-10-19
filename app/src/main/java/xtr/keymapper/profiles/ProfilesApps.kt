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

    /**
     * Loading apps is a time consuming process on slow devices with a large number of apps.
     * We show a visual loading indicator while loading the apps in a separate thread
     * @param context UI context
     * @param builder builder for creating a dialog with visual loading indicator and showing
     * @param onAppsLoadedListener Called when finished loading apps
     */
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
        // Inflate the layout for this fragment
        binding = FragmentProfilesAppsBinding.inflate(inflater)
        return binding.root
    }

    fun onDestroyView() {
        // Java nulled binding here; lateinit can't. The instance is dropped
        // immediately after by both callers, so the view refs die with it.
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

        // Inflate Layout
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerViewHolder {
            val itemBinding =
                AppViewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return RecyclerViewHolder(itemBinding) { position ->
                mListener!!.onAppSelected(appsDataArrayList[position].packageName)
            }
        }

        override fun onBindViewHolder(holder: RecyclerViewHolder, position: Int) {
            // Set the data to textview and imageview.
            val recyclerData = appsDataArrayList[position]
            holder.binding.appName.text = recyclerData.title
            holder.binding.appIcon.setImageDrawable(recyclerData.icon)
        }

        override fun getItemCount(): Int {
            // this method returns the size of recyclerview
            return appsDataArrayList.size
        }
    }

    // Kotlin forbids nested classes inside inner classes, so this lives at
    // the outer class level (still file-private to ProfilesApps users).
    private class RecyclerData(
        val packageName: String,
        val title: CharSequence,
        val icon: Drawable
    )

    // View Holder Class to handle Recycler View.
    // Java had a non-static inner holder reaching into the adapter and the
    // outer listener; Kotlin forbids inner types in their own class header,
    // so it is a sibling class and the click target is passed as a lambda.
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
