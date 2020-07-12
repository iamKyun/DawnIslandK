/*
 *  Copyright 2020 Fishballzzz
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.laotoua.dawnislandk.screens.comments


import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import android.text.style.UnderlineSpan
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.text.toSpannable
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.google.android.material.animation.AnimationUtils
import com.laotoua.dawnislandk.DawnApp
import com.laotoua.dawnislandk.MainNavDirections
import com.laotoua.dawnislandk.R
import com.laotoua.dawnislandk.data.local.entity.Comment
import com.laotoua.dawnislandk.databinding.FragmentCommentBinding
import com.laotoua.dawnislandk.di.DaggerViewModelFactory
import com.laotoua.dawnislandk.screens.MainActivity
import com.laotoua.dawnislandk.screens.SharedViewModel
import com.laotoua.dawnislandk.screens.adapters.QuickAdapter
import com.laotoua.dawnislandk.screens.util.Layout.updateHeaderAndFooter
import com.laotoua.dawnislandk.screens.widgets.LinkifyTextView
import com.laotoua.dawnislandk.screens.widgets.popups.ImageViewerPopup
import com.laotoua.dawnislandk.screens.widgets.popups.PostPopup
import com.laotoua.dawnislandk.screens.widgets.spans.ReferenceSpan
import com.laotoua.dawnislandk.util.EventPayload
import com.laotoua.dawnislandk.util.SingleLiveEvent
import com.laotoua.dawnislandk.util.lazyOnMainOnly
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.interfaces.SimpleCallback
import dagger.android.support.DaggerFragment
import me.dkzwm.widget.srl.RefreshingListenerAdapter
import me.dkzwm.widget.srl.config.Constants
import timber.log.Timber
import javax.inject.Inject


class CommentsFragment : DaggerFragment() {
    private val args: CommentsFragmentArgs by navArgs()

    private var _binding: FragmentCommentBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var viewModelFactory: DaggerViewModelFactory
    private val viewModel: CommentsViewModel by viewModels { viewModelFactory }
    private val sharedVM: SharedViewModel by activityViewModels { viewModelFactory }

    private var _mAdapter: QuickAdapter<Comment>? = null
    private val mAdapter get() = _mAdapter!!

    // last visible item indicates the current page, uses for remembering last read page
    private var currentPage = 0
    private var pageCounter: TextView? = null
    private var filterActivated: Boolean = false
    private var requireTitleUpdate: Boolean = false

    // list to remember all currently displaying popups
    // need to dismiss all before jumping to new post, by lifo
    private val quotePopups: MutableList<QuotePopup> = mutableListOf()
    private val postPopup: PostPopup by lazyOnMainOnly { PostPopup(requireActivity(), sharedVM) }
    private val jumpPopup: JumpPopup by lazyOnMainOnly { JumpPopup(requireContext()) }

    enum class RVScrollState {
        UP,
        DOWN
    }

    private var currentState: RVScrollState? = null
    private var currentAnimatorSet: ViewPropertyAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_fragment_comment, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        pageCounter = menu.findItem(R.id.pageCounter).actionView.findViewById(R.id.text)
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.filter -> {
                filterActivated = filterActivated.not()
                if (!filterActivated) {
                    viewModel.clearFilter()
                    Toast.makeText(context, R.string.comment_filter_off, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.onlyPo()
                    Toast.makeText(context, R.string.comment_filter_on, Toast.LENGTH_SHORT).show()
                }
                (binding.srlAndRv.recyclerView.layoutManager as LinearLayoutManager).run {
                    val startPos = findFirstVisibleItemPosition()
                    val itemCount = findLastVisibleItemPosition() - startPos
                    mAdapter.notifyItemRangeChanged(startPos, itemCount + initialPrefetchItemCount)
                }
                return true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        if (_mAdapter == null) {
            _mAdapter = QuickAdapter<Comment>(R.layout.list_item_comment, sharedVM).apply {
                setReferenceClickListener(object : ReferenceSpan.ReferenceClickHandler {
                    override fun handleReference(id: String) {
                        displayQuote(id)
                    }
                })

                setOnItemClickListener { _, _, pos ->
                    toggleCommentMenuOnPos(pos)
                }

                addChildClickViewIds(
                    R.id.attachedImage,
                    R.id.expandSummary,
                    R.id.comment,
                    R.id.content,
                    R.id.copy,
                    R.id.report
                )

                setOnItemChildClickListener { _, view, position ->
                    when (view.id) {
                        R.id.attachedImage -> {
                            val url = getItem(position).getImgUrl()
                            // TODO support multiple image
                            val viewerPopup =
                                ImageViewerPopup(url, requireContext())
                            viewerPopup.setSingleSrcView(view as ImageView?, url)

                            XPopup.Builder(context)
                                .asCustom(viewerPopup)
                                .show()
                        }
                        R.id.comment -> {
                            val content = ">>No.${getItem(position).id}\n"
                            postPopup.setupAndShow(
                                viewModel.currentPostId,
                                viewModel.currentPostFid,
                                targetPage = viewModel.maxPage,
                                quote = content
                            )
                        }
                        R.id.copy -> {
                            mAdapter.getViewByPosition(position, R.id.content)?.let {
                                copyText("评论", (it as TextView).text.toString())
                            }
                        }
                        R.id.report -> {
                            MaterialDialog(requireContext()).show {
                                title(R.string.report_reasons)
                                listItemsSingleChoice(res = R.array.report_reasons) { _, _, text ->
                                    postPopup.setupAndShow(
                                        "18",//值班室
                                        "18",
                                        newPost = true,
                                        quote = "\n>>No.${getItem(position).id}\n${context.getString(
                                            R.string.report_reasons
                                        )}: $text"
                                    )
                                }
                                cancelOnTouchOutside(false)
                            }
                        }
                        R.id.content -> {
                            val ltv = view as LinkifyTextView
                            // no span was clicked, simulate click events to parent
                            if (ltv.currentSpan == null) {
                                val metaState = 0
                                (view.parent as View).dispatchTouchEvent(
                                    MotionEvent.obtain(
                                        SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_DOWN,
                                        0f,
                                        0f,
                                        metaState
                                    )
                                )
                                (view.parent as View).dispatchTouchEvent(
                                    MotionEvent.obtain(
                                        SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_UP,
                                        0f,
                                        0f,
                                        metaState
                                    )
                                )
                            }
                        }
                        R.id.expandSummary -> {
                            data[position].visible = true
                            notifyItemChanged(position)
                        }
                    }
                }

                // load more
                loadMoreModule.setOnLoadMoreListener {
                    viewModel.getNextPage()
                }
            }
        }

        if (_binding != null) {
            Timber.d("Fragment View Reusing!")
        } else {
            Timber.d("Fragment View Created")
            _binding = FragmentCommentBinding.inflate(inflater, container, false)
            binding.srlAndRv.refreshLayout.apply {
                setOnRefreshListener(object : RefreshingListenerAdapter() {
                    override fun onRefreshing() {
                        if (!mAdapter.data.isNullOrEmpty() && mAdapter.getItem(
                                (binding.srlAndRv.recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                            ).page == 1
                        ) {
                            Toast.makeText(context, "没有上一页了。。。", Toast.LENGTH_SHORT).show()
                            refreshComplete(true, 100L)
                        } else {
                            viewModel.getPreviousPage()
                        }
                    }
                })
            }

            binding.srlAndRv.recyclerView.apply {
                val llm = LinearLayoutManager(context)
                layoutManager = llm
                adapter = mAdapter
                setHasFixedSize(true)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (dy > 0) {
                            hideMenu()
                        } else if (dy < 0) {
                            showMenu()
                            if (llm.findFirstVisibleItemPosition() <= 2 && !binding.srlAndRv.refreshLayout.isRefreshing) {
                                viewModel.getPreviousPage()
                            }
                        }
                        updateCurrentPage()
                    }
                })
            }

            binding.copyId.setOnClickListener {
                copyText("串号", ">>No.${viewModel.currentPostId}")
            }

            binding.post.setOnClickListener {
                val page = getCurrentPage(mAdapter)
                postPopup.setupAndShow(
                    viewModel.currentPostId,
                    viewModel.currentPostFid,
                    targetPage = viewModel.maxPage
                ) {
                    if (page == viewModel.maxPage) {
                        mAdapter.loadMoreModule.loadMoreToLoading()
                    }
                }
            }

            binding.jump.setOnClickListener {
                if (binding.srlAndRv.refreshLayout.isRefreshing || mAdapter.loadMoreModule.isLoading) {
                    Timber.d("Loading data...Holding on jump...")
                    return@setOnClickListener
                }
                val page = getCurrentPage(mAdapter)
                XPopup.Builder(context)
                    .setPopupCallback(object : SimpleCallback() {
                        override fun beforeShow() {
                            super.beforeShow()
                            jumpPopup.updatePages(page, viewModel.maxPage)
                        }

                        override fun onDismiss() {
                            super.onDismiss()
                            if (jumpPopup.submit) {
                                binding.srlAndRv.refreshLayout.autoRefresh(
                                    Constants.ACTION_NOTHING,
                                    false
                                )
                                mAdapter.setList(emptyList())
                                Timber.i("Jumping to ${jumpPopup.targetPage}...")
                                viewModel.jumpTo(jumpPopup.targetPage)
                            }
                        }
                    })
                    .asCustom(jumpPopup)
                    .show()
            }

            binding.addFeed.setOnClickListener {
                viewModel.addFeed(viewModel.currentPostId)
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.setPost(args.id, args.fid, args.targetPage)
        requireTitleUpdate = args.fid.isBlank()
        updateTitle()
        updateCurrentPage()
    }

    private val addFeedObs = Observer<SingleLiveEvent<String>> {
        it.getContentIfNotHandled()?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private val loadingStatusObs = Observer<SingleLiveEvent<EventPayload<Nothing>>> {
        if (_binding == null || _mAdapter == null) return@Observer
        it.getContentIfNotHandled()?.run {
            updateHeaderAndFooter(binding.srlAndRv.refreshLayout, mAdapter, this)
        }
    }

    private val commentsObs = Observer<MutableList<Comment>> {
        if (_mAdapter == null || it.isEmpty()) return@Observer
        updateCurrentPage()
        if (requireTitleUpdate) {
            updateTitle()
            requireTitleUpdate = false
        }
        mAdapter.setDiffNewData(it.toMutableList())
        mAdapter.setPo(viewModel.po)
        Timber.i("${this.javaClass.simpleName} Adapter will have ${mAdapter.data.size} threads")
    }

    private fun subscribeUI() {
        viewModel.addFeedResponse.observe(viewLifecycleOwner, addFeedObs)
        viewModel.loadingStatus.observe(viewLifecycleOwner, loadingStatusObs)
        viewModel.comments.observe(viewLifecycleOwner, commentsObs)
    }

    private fun unsubscribeUI() {
        viewModel.addFeedResponse.removeObserver(addFeedObs)
        viewModel.loadingStatus.removeObserver(loadingStatusObs)
        viewModel.comments.removeObserver(commentsObs)
    }

    override fun onPause() {
        super.onPause()
        unsubscribeUI()
    }

    override fun onResume() {
        super.onResume()
        subscribeUI()

        (requireActivity() as MainActivity).run {
            setToolbarClickListener {
                binding.srlAndRv.recyclerView.layoutManager?.scrollToPosition(0)
                showMenu()
            }
            hideNav()
        }
    }

    private fun copyText(label: String, text: String) {
        getSystemService(requireContext(), ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
        if (label == "串号") Toast.makeText(context, R.string.post_id_copied, Toast.LENGTH_SHORT)
            .show()
        else Toast.makeText(context, R.string.comment_copied, Toast.LENGTH_SHORT).show()
    }

    private fun getCurrentPage(adapter: QuickAdapter<Comment>): Int {
        if (_mAdapter == null || mAdapter.data.isNullOrEmpty()) return 1
        val pos = (binding.srlAndRv.recyclerView.layoutManager as LinearLayoutManager)
            .findLastVisibleItemPosition()
            .coerceAtLeast(0)
            .coerceAtMost(adapter.data.lastIndex)
        return adapter.getItem(pos).page
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissAllQuotes()
        if (!DawnApp.applicationDataStore.viewCaching) {
            _mAdapter = null
            _binding = null
        }
        Timber.d("Fragment View Destroyed ${_binding == null}")
    }

    fun hideMenu() {
        if (currentState == RVScrollState.DOWN) return
        if (currentAnimatorSet != null) {
            currentAnimatorSet!!.cancel()
        }
        currentState = RVScrollState.DOWN
        currentAnimatorSet = binding.bottomToolbar.animate().apply {
            alpha(0f)
            translationY(binding.bottomToolbar.height.toFloat())
            duration = 250
            interpolator = AnimationUtils.FAST_OUT_LINEAR_IN_INTERPOLATOR
            setListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator?) {}
                override fun onAnimationEnd(animation: Animator?) {
                    currentAnimatorSet = null
                    binding.bottomToolbar.visibility = View.GONE
                }

                override fun onAnimationCancel(animation: Animator?) {}
                override fun onAnimationStart(animation: Animator?) {}
            })
        }
        currentAnimatorSet!!.start()
    }

    fun showMenu() {
        if (currentState == RVScrollState.UP) return
        if (currentAnimatorSet != null) {
            currentAnimatorSet!!.cancel()
        }
        currentState = RVScrollState.UP
        binding.bottomToolbar.visibility = View.VISIBLE
        currentAnimatorSet = binding.bottomToolbar.animate().apply {
            alpha(1f)
            translationY(0f)
            duration = 250
            interpolator = AnimationUtils.LINEAR_OUT_SLOW_IN_INTERPOLATOR
            setListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator?) {}
                override fun onAnimationEnd(animation: Animator?) {
                    currentAnimatorSet = null
                }

                override fun onAnimationCancel(animation: Animator?) {}
                override fun onAnimationStart(animation: Animator?) {}
            })
        }
        currentAnimatorSet!!.start()
    }

    private fun updateTitle() {
        if (viewModel.currentPostFid.isNotBlank()) {
            (requireActivity() as MainActivity).setToolbarTitle(
                "${sharedVM.getSelectedPostForumName(viewModel.currentPostFid)} • ${viewModel.currentPostId}"
            )
        }
    }

    private fun updateCurrentPage() {
        val lastVisiblePos =
            (_binding?.srlAndRv?.recyclerView?.layoutManager as LinearLayoutManager?)?.findLastVisibleItemPosition()
                ?: 0
        if (0 <= lastVisiblePos && lastVisiblePos < mAdapter.data.size) {
            val page = mAdapter.getItem(lastVisiblePos).page
            if (page != currentPage || pageCounter?.text?.isBlank() == true) {
                if (page != currentPage) viewModel.saveReadingProgress(page)
                pageCounter?.text =
                    (page.toString() + " / " + viewModel.maxPage.toString()).toSpannable()
                        .apply { setSpan(UnderlineSpan(), 0, length, 0) }
                currentPage = page
            }
        }
    }

    private var menuPos = -1

    private fun showCommentMenuOnPos(pos: Int) {
        menuPos = pos
        mAdapter.getViewByPosition(pos, R.id.commentMenu)?.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(150)
                .setListener(null)
        }
    }

    private fun hideCommentMenuOnPos(pos: Int) {
        if (menuPos < 0) return
        mAdapter.getViewByPosition(pos, R.id.commentMenu)?.apply {
            animate()
                .alpha(0f)
                .setDuration(150)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = View.GONE
                    }
                })
        }
    }

    private fun toggleCommentMenuOnPos(pos: Int) {
        mAdapter.getViewByPosition(pos, R.id.commentMenu)?.apply {
            if (isVisible) {
                hideCommentMenuOnPos(pos)
            } else {
                hideCommentMenuOnPos(menuPos)
                showCommentMenuOnPos(pos)
            }
        }
    }

    fun displayQuote(id: String) {
        val top = QuotePopup(this, viewModel.getQuote(id), viewModel.currentPostId, viewModel.po)
        quotePopups.add(top)
        XPopup.Builder(context)
            .setPopupCallback(object : SimpleCallback() {
                override fun beforeShow() {
                    super.beforeShow()
                    top.listenToLiveQuote()
                }
            })
            .asCustom(top)
            .show()
    }

    fun dismissQuote(quotePopup: QuotePopup) {
        quotePopups.remove(quotePopup)
    }

    private fun dismissAllQuotes() {
        for (i in quotePopups.indices.reversed()) {
            quotePopups[i].smartDismiss()
            quotePopups.removeAt(i)
        }
    }

    fun jumpToNewPost(id: String) {
        dismissAllQuotes()
        val navAction = MainNavDirections.actionGlobalCommentsFragment(id, "")
        findNavController().navigate(navAction)
    }
}
