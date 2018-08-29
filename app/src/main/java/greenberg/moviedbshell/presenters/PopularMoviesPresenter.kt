package greenberg.moviedbshell.presenters

import android.content.Context
import android.os.Bundle
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter
import greenberg.moviedbshell.R
import greenberg.moviedbshell.mappers.PopularMovieMapper
import greenberg.moviedbshell.models.ui.MovieItem
import greenberg.moviedbshell.services.TMDBService
import greenberg.moviedbshell.view.PopularMoviesView
import greenberg.moviedbshell.viewHolders.PopularMovieAdapter
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import okhttp3.OkHttpClient
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class PopularMoviesPresenter
@Inject constructor(private val TMDBService: TMDBService,
                    private val httpClient: OkHttpClient,
                    private val context: Context,
                    private val mapper: PopularMovieMapper) : MvpBasePresenter<PopularMoviesView>() {

    private var isRecyclerLoading = false
    //Default to getting the first page
    private var popularMoviePageNumber = 1
    private var totalAvailablePages = -1
    //private var mapper: PopularMovieMapper = PopularMovieMapper()
    private var popularMoviesList = mutableListOf<MovieItem>()
    private var popularMovieAdapter: PopularMovieAdapter? = null
    private var compositeDisposable = CompositeDisposable()
    private var loadedMaxPages = false

    override fun attachView(view: PopularMoviesView) {
        super.attachView(view)
        Timber.d("attachView")
    }

    fun initView() {
        ifViewAttached { view: PopularMoviesView ->
            view.showLoading(true)
        }
    }

    fun initRecyclerPagination(recyclerView: RecyclerView?, adapter: PopularMovieAdapter?) {
        this.popularMovieAdapter = adapter
        recyclerView?.apply {
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val layoutManager = this@apply.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    if (!isRecyclerLoading
                            && (visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                            && firstVisibleItemPosition >= 0) {
                        ifViewAttached { view: PopularMoviesView ->
                            isRecyclerLoading = true
                            if (!loadedMaxPages) {
                                view.showPageLoad()
                                fetchNextPage()
                            }
                        }
                    }
                }
            })
        }
    }

    fun loadPopularMoviesList(pullToRefresh: Boolean) {
        if (popularMoviesList.isEmpty()) {
            Timber.d("Getting new popular movie list")
            popularMoviePageNumber = 1
            val disposable =
                    TMDBService.queryPopularMovies(popularMoviePageNumber)
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe({ response ->
                                ifViewAttached { view: PopularMoviesView ->
                                    popularMovieAdapter?.popularMovieList?.addAll(mapper.mapToEntity(response))
                                    popularMovieAdapter?.notifyDataSetChanged()
                                    view.showMovies()
                                    totalAvailablePages = response.totalPages ?: -1
                                }
                            }, { throwable ->
                                ifViewAttached { view: PopularMoviesView ->
                                    view.showError(throwable, pullToRefresh)
                                }
                            })
            compositeDisposable.add(disposable)
        } else {
            ifViewAttached { view: PopularMoviesView ->
                //Copy last good list into adapter
                Timber.d("Getting old list and showing")
                popularMovieAdapter?.popularMovieList = popularMoviesList
                popularMovieAdapter?.notifyDataSetChanged()
                view.showMovies()
            }
        }
    }

    fun refreshPage() {
        ifViewAttached { view: PopularMoviesView ->
            evictCachedUrls()
            popularMovieAdapter?.popularMovieList?.clear()
            popularMovieAdapter?.notifyDataSetChanged()
            popularMoviesList.clear()
            view.showLoading(true)
        }
    }

    //Gets next page of popular movies call
    private fun fetchNextPage() {
        if (popularMoviePageNumber < totalAvailablePages && totalAvailablePages != -1) {
            Timber.d("Fetching next page: $popularMoviePageNumber")
            val disposable =
                    TMDBService.queryPopularMovies(++popularMoviePageNumber)
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe({ response ->
                                ifViewAttached { view: PopularMoviesView ->
                                    Timber.d("Next page fetched")
                                    popularMovieAdapter?.popularMovieList?.addAll(mapper.mapToEntity(response))
                                    popularMovieAdapter?.notifyDataSetChanged()
                                    view.hidePageLoad()
                                    isRecyclerLoading = false
                                }
                            }, { throwable ->
                                ifViewAttached { view: PopularMoviesView ->
                                    //todo: revisit erroring the whole page on this.  Just error bottom
                                    view.showError(throwable, false)
                                }
                            })
            compositeDisposable.add(disposable)
        } else {
            ifViewAttached { view: PopularMoviesView ->
                view.hidePageLoad()
                view.showMaxPages()
                isRecyclerLoading = false
                loadedMaxPages = true
            }
        }
        //TODO: else handle error for no available pages
    }

    fun onCardSelected(position: Int) {
        ifViewAttached { view: PopularMoviesView ->
            view.showDetail(Bundle().apply {
                putInt("MovieID", position)
            })
        }
    }

    fun processReleaseDate(releaseDate: String?): String =
            if (releaseDate?.isNotBlank() == true) {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = inputFormat.parse(releaseDate)
                val outputFormat = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
                outputFormat.format(date)
            } else {
                ""
            }

    private fun evictCachedUrls() {
        val iterator = httpClient.cache()?.urls()
        while (iterator?.hasNext() == true) {
            val currentUrl = iterator.next()
            if (currentUrl.contains(context.getString(R.string.tmdb_popular_url))) {
                Timber.d("Removed $currentUrl from OkHttpCache")
                iterator.remove()
            }
        }
    }

    override fun detachView() {
        Timber.d("Detach view")
        ifViewAttached { view: PopularMoviesView ->
            view.hidePageLoad()
            view.hideMaxPages()
        }
        //Copy last good list or empty. Maybe log if empty
        popularMoviesList = popularMovieAdapter?.popularMovieList?.toMutableList() ?: mutableListOf()
        super.detachView()
    }

    override fun destroy() {
        super.destroy()
        Timber.d("destroy called, disposables disposed of")
        compositeDisposable.dispose()
    }
}