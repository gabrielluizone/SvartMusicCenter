package com.matejdro.wearmusiccenter.watch.view

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.support.wearable.input.WearableButtons
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.CurvingLayoutCallback
import androidx.wear.widget.WearableLinearLayoutManager
import androidx.wear.widget.WearableRecyclerView
import com.google.android.wearable.input.RotaryEncoderHelper
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.common.CustomLists
import com.matejdro.wearmusiccenter.common.MiscPreferences
import com.matejdro.wearmusiccenter.watch.communication.CustomListWithBitmaps
import com.matejdro.wearmusiccenter.watch.communication.WatchInfoSender
import com.matejdro.wearmusiccenter.watch.config.ButtonAction
import com.matejdro.wearutils.preferences.definition.Preferences
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs


@SuppressLint("NotifyDataSetChanged")
@AndroidEntryPoint
class ActionsMenuFragment : Fragment() {
    private val viewmodel: MusicViewModel by activityViewModels()

    private lateinit var recycler: WearableRecyclerView
    private lateinit var recyclerClickDetector: RecyclerClickDetector

    private var menuItems: List<ButtonAction> = emptyList()
    private var customMenuItems: CustomListWithBitmaps? = null
    private lateinit var adapter: MenuAdapter
    private lateinit var layoutManager: MenuLayoutManager

    private var closeDrawerKeycode = -1

    private lateinit var activity: MainActivity

    private var alwaysPickCenter = false

    override fun onAttach(context: Context) {
        super.onAttach(context)

        activity = context as MainActivity

        viewmodel.actionsMenuConfig.config.observe(activity, actionItemsListener)
        viewmodel.preferences.observe(activity, preferencesListener)

        findButtons()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        recyclerClickDetector = inflater.inflate(R.layout.fragment_actions_list, container, false)
                as RecyclerClickDetector
        return recyclerClickDetector
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MenuAdapter()

        recycler = view.findViewById(R.id.recycler)

        layoutManager = MenuLayoutManager(requireContext())
        recycler.layoutManager = layoutManager
        recycler.isEdgeItemsCenteringEnabled = true
        recycler.adapter = adapter

        recyclerClickDetector.setOnClickListener {
            layoutManager.getCenterItem()?.let { index -> executeAction(index) }
        }

    }

    fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (RotaryEncoderHelper.isFromRotaryEncoder(ev) && WatchInfoSender.hasDiscreteRotaryInput()) {
            val moveForward = RotaryEncoderHelper.getRotaryAxisValue(ev) < 0

            val currentPosition = layoutManager.getCenterItem() ?: return true

            val targetPosition = if (moveForward) {
                (currentPosition + 1).coerceAtMost(adapter.itemCount - 1)
            } else {
                (currentPosition - 1).coerceAtLeast(0)
            }

            layoutManager.scrollToPosition(targetPosition)

            return true
        }
        return false
    }

    private val actionItemsListener = Observer<List<ButtonAction>?> {
        if (it == null) {
            return@Observer
        }

        menuItems = it
        adapter.notifyDataSetChanged()
    }

    private val preferencesListener = Observer<SharedPreferences?> {
        if (it == null) {
            return@Observer
        }

        alwaysPickCenter = Preferences.getBoolean(it, MiscPreferences.ALWAYS_SELECT_CENTER_ACTION)
        recyclerClickDetector.isClickable = alwaysPickCenter


        adapter.notifyDataSetChanged()
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun findButtons() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
            return
        }

        val numButtons = WearableButtons.getButtonCount(context)
        if (numButtons <= 1) {
            // We have at most 1 button. Lets use that lone button for confirm action
            return
        }

        //Find button with lowest Y value (furthest from user) - that will be close button
        closeDrawerKeycode = (KeyEvent.KEYCODE_STEM_1..KeyEvent.KEYCODE_STEM_3)
                .mapNotNull { WearableButtons.getButtonInfo(context, it) }
                .minByOrNull { it.y }?.keycode ?: -1
    }

    fun scrollToTop() {
        recycler.scrollToPosition(0)
    }

    fun refreshMenu(type: MenuType) {
        val newCustomMenuItems = when (type) {
            is MenuType.Actions -> null
            is MenuType.Custom -> type.items
        }

        if (newCustomMenuItems != customMenuItems) {
            customMenuItems = newCustomMenuItems
            adapter.notifyDataSetChanged()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == closeDrawerKeycode) {
            (context as MainActivity).closeMenuDrawer()
        } else {
            layoutManager.getCenterItem()?.let { executeAction(it) }
        }
        return true
    }

    private fun executeAction(index: Int) {
        val customList = customMenuItems
        if (customList != null) {
            if (customList.listId == CustomLists.HISTORY) {
                return
            }
            viewmodel.executeItemFromCustomMenu(
                    customList.listId,
                    customList.items.elementAt(index).listItem.entryId
            )
        } else {
            viewmodel.executeActionFromMenu(index)
        }
    }

    inner class MenuAdapter : RecyclerView.Adapter<MenuItemViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuItemViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_list_action, parent, false)
            return MenuItemViewHolder(view)
        }

        override fun onBindViewHolder(holder: MenuItemViewHolder, position: Int) {
            val customMenuItems = customMenuItems
            if (customMenuItems != null) {
                val customItem = customMenuItems.items[position]

                // Matches the stock Wear OS queue look: plain text rows (title + artist), no
                // per-entry album art thumbnail, pill-shaped background instead of the regular
                // action list's glass card.
                holder.icon.visibility = View.GONE
                holder.itemView.background = AppCompatResources.getDrawable(requireContext(), R.drawable.queue_pill_background)
                holder.title.text = customItem.listItem.entryTitle

                val subtitle = customItem.listItem.entrySubtitle
                holder.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
                holder.subtitle.text = subtitle

                // History entries (the fallback shown when the playing app, e.g. YouTube Music,
                // doesn't expose a real skippable queue) are informational only - there is no
                // queue position to jump back to, so making them look tappable would just look
                // broken when nothing happens after tapping one.
                val isInformationalOnly = customMenuItems.listId == CustomLists.HISTORY
                val clickListener = if (alwaysPickCenter || isInformationalOnly) null else holder
                holder.itemView.setOnClickListener(clickListener)
                holder.itemView.isClickable = !alwaysPickCenter && !isInformationalOnly
            } else {
                val configItem = menuItems[position]

                holder.icon.visibility = View.VISIBLE
                holder.icon.setImageDrawable(configItem.icon)
                holder.itemView.background = AppCompatResources.getDrawable(requireContext(), R.drawable.glass_card_background)
                holder.title.text = configItem.title
                holder.subtitle.visibility = View.GONE

                val clickListener = if (alwaysPickCenter) null else holder
                holder.itemView.setOnClickListener(clickListener)
                holder.itemView.isClickable = !alwaysPickCenter
            }
        }

        override fun getItemCount(): Int {
            return customMenuItems?.items?.size ?: menuItems.size
        }

    }

    inner class MenuItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener {
        val icon: ImageView = itemView.findViewById(R.id.icon)
        val title: TextView = itemView.findViewById(R.id.text)
        val subtitle: TextView = itemView.findViewById(R.id.text_subtitle)

        init {
            itemView.tag = this
        }

        override fun onClick(v: View?) {
            activity.buzz()
            executeAction(bindingAdapterPosition)
        }
    }

    private inner class MenuLayoutManager(context: Context) : WearableLinearLayoutManager(context) {
        private val roundScreen: Boolean = resources.configuration.isScreenRound

        private var closestDistToCenter = Float.MAX_VALUE
        private var closestChildViewHolder: MenuItemViewHolder? = null

        fun getCenterItem(): Int? {
            val closestChildViewHolder = closestChildViewHolder
                    ?: return null

            return closestChildViewHolder.bindingAdapterPosition
        }

        private fun prepareChildrenLayout() {
            closestDistToCenter = Float.MAX_VALUE
            closestChildViewHolder = null
        }

        private fun finishChildrenLayout() {
            // Custom lists (queue/history) are flat, fully-opaque modern rows - the dimming/
            // scaling below was designed for the old single-line icon rows and looks dated
            // against the new pill-shaped, two-line rows.
            if (customMenuItems != null) {
                for (childIndex in 0 until childCount) {
                    getChildAt(childIndex)?.alpha = 1f
                }
                return
            }

            for (childIndex in 0 until childCount) {
                val child = getChildAt(childIndex)
                val holder = child!!.tag as MenuItemViewHolder

                val textScale: Float
                val imageScale: Float
                if (holder == closestChildViewHolder) {
                    textScale = 1f
                    imageScale = 1f
                } else {
                    textScale = 0.5f
                    imageScale = 0.75f
                }

                child.alpha = textScale

                if (roundScreen) {
                    holder.icon.scaleX = imageScale
                    holder.icon.scaleY = imageScale
                }

            }
        }

        override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
            prepareChildrenLayout()
            val ret = super.scrollVerticallyBy(dy, recycler, state)
            finishChildrenLayout()
            return ret
        }

        override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
            prepareChildrenLayout()
            super.onLayoutChildren(recycler, state)
            finishChildrenLayout()
        }

        private val childLayoutCallback = object : CurvingLayoutCallback(context) {
            override fun onLayoutFinished(child: View, parent: RecyclerView?) {
                if (customMenuItems != null) {
                    // Flat rows for queue/history lists - the curvature below is what makes the
                    // list look like it's bending around a circle while scrolling.
                    child.translationX = 0f
                    child.scaleX = 1f
                    child.scaleY = 1f
                } else {
                    super.onLayoutFinished(child, parent)
                }

                // Figure out % progress from top to bottom
                val centerOffset = child.height.toFloat() / 2.0f / recycler.height.toFloat()
                val yRelativeToCenterOffset = child.y / recycler.height + centerOffset

                // Normalize for center
                val progressToCenter = abs(0.5f - yRelativeToCenterOffset)

                val holder = child.tag as MenuItemViewHolder

                if (closestDistToCenter > progressToCenter) {
                    closestChildViewHolder = holder
                    closestDistToCenter = progressToCenter
                }
            }

        }

        init {
            layoutCallback = childLayoutCallback
        }
    }

    sealed class MenuType {
        object Actions : MenuType()
        class Custom(val items: CustomListWithBitmaps) : MenuType()
    }
}
