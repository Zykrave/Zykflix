package com.zykrave.zykflix.fragments.season

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zykrave.zykflix.R
import com.zykrave.zykflix.adapters.AppAdapter
import com.zykrave.zykflix.database.AppDatabase
import com.zykrave.zykflix.databinding.FragmentSeasonMobileBinding
import com.zykrave.zykflix.models.Episode
import com.zykrave.zykflix.ui.SpacingItemDecoration
import com.zykrave.zykflix.utils.CacheUtils
import com.zykrave.zykflix.utils.LoggingUtils
import com.zykrave.zykflix.utils.dp
import com.zykrave.zykflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class SeasonMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentSeasonMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<SeasonMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory {
        SeasonViewModel(
            args.seasonId,
            args.tvShowId,
            database
        )
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSeasonMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSeason()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    SeasonViewModel.State.LoadingEpisodes -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is SeasonViewModel.State.SuccessLoadingEpisodes -> {
                        displaySeason(state.episodes)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is SeasonViewModel.State.FailedLoadingEpisodes -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.zykrave.zykflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getSeasonEpisodes(args.seasonId)
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                            binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.getSeasonEpisodes(args.seasonId) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.zykrave.zykflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializeSeason() {
        binding.tvSeasonTitle.text = args.seasonTitle

        binding.rvEpisodes.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }
    }

    private fun displaySeason(episodes: List<Episode>) {
        appAdapter.submitList(episodes.onEach { episode ->
            episode.itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
        })

        if (episodes.size > 50) {
            binding.hsvEpisodeRanges.visibility = View.VISIBLE
            binding.llEpisodeRanges.removeAllViews()
            val chunkSize = 50
            val layoutManager = binding.rvEpisodes.layoutManager as? LinearLayoutManager
            var start = 0
            while (start < episodes.size) {
                val end = minOf(start + chunkSize, episodes.size)
                val rangeLabel = "${start + 1}-$end"
                val button = TextView(requireContext()).apply {
                    text = rangeLabel
                    TextViewCompat.setTextAppearance(this, R.style.AppTheme_Widget_Mobile_Button_Secondary)
                    background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_secondary_mobile)
                    setPadding(24.dp(requireContext()), 8.dp(requireContext()), 24.dp(requireContext()), 8.dp(requireContext()))
                    val marginParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    marginParams.marginEnd = 8.dp(requireContext())
                    layoutParams = marginParams
                    setOnClickListener {
                        layoutManager?.scrollToPositionWithOffset(start, 0)
                    }
                }
                binding.llEpisodeRanges.addView(button)
                start += chunkSize
            }
        } else {
            binding.hsvEpisodeRanges.visibility = View.GONE
        }

        val episodeIndex = episodes
            .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
            .firstOrNull { it.watchHistory != null }
            ?.let { episodes.indexOf(it) }
            ?: episodes.indexOfLast { it.isWatched }
                .takeIf { it != -1 && it + 1 < episodes.size }
                ?.let { it + 1 }

        if (episodeIndex != null) {
            val layoutManager = binding.rvEpisodes.layoutManager as? LinearLayoutManager
            layoutManager?.scrollToPositionWithOffset(
                episodeIndex,
                binding.rvEpisodes.height / 2 - 100.dp(requireContext())
            )
        }
    }
}