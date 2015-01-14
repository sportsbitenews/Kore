/*
 * Copyright 2015 Synced Synapse. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.syncedsynapse.kore2.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.BaseColumns;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SwipeRefreshLayout;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.melnykov.fab.FloatingActionButton;
import com.melnykov.fab.ObservableScrollView;
import com.syncedsynapse.kore2.R;
import com.syncedsynapse.kore2.host.HostInfo;
import com.syncedsynapse.kore2.host.HostManager;
import com.syncedsynapse.kore2.jsonrpc.ApiCallback;
import com.syncedsynapse.kore2.jsonrpc.ApiException;
import com.syncedsynapse.kore2.jsonrpc.event.MediaSyncEvent;
import com.syncedsynapse.kore2.jsonrpc.method.Player;
import com.syncedsynapse.kore2.jsonrpc.method.Playlist;
import com.syncedsynapse.kore2.jsonrpc.type.PlaylistType;
import com.syncedsynapse.kore2.provider.MediaContract;
import com.syncedsynapse.kore2.service.LibrarySyncService;
import com.syncedsynapse.kore2.utils.FileDownloadHelper;
import com.syncedsynapse.kore2.utils.LogUtils;
import com.syncedsynapse.kore2.utils.UIUtils;

import java.io.File;
import java.util.ArrayList;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import de.greenrobot.event.EventBus;

/**
 * Presents music videos details
 */
public class MusicVideoDetailsFragment extends Fragment
        implements LoaderManager.LoaderCallbacks<Cursor>,
        SwipeRefreshLayout.OnRefreshListener {
    private static final String TAG = LogUtils.makeLogTag(MusicVideoDetailsFragment.class);

    public static final String MUSICVIDEOID = "music_video_id";

    // Loader IDs
    private static final int LOADER_MUSIC_VIDEO = 0;

    private HostManager hostManager;
    private HostInfo hostInfo;
    private EventBus bus;

    /**
     * Handler on which to post RPC callbacks
     */
    private Handler callbackHandler = new Handler();

    // Displayed music video id
    private int musicVideoId = -1;

    // Info for downloading the music video
    private FileDownloadHelper.MusicVideoInfo musicVideoDownloadInfo = null;

    // Controls whether the finished refreshing message is shown
    private boolean showRefreshStatusMessage = true;

    @InjectView(R.id.swipe_refresh_layout) SwipeRefreshLayout swipeRefreshLayout;

    // Buttons
    @InjectView(R.id.fab) ImageButton fabButton;
    @InjectView(R.id.add_to_playlist) ImageButton addToPlaylistButton;
    @InjectView(R.id.download) ImageButton downloadButton;

    // Detail views
    @InjectView(R.id.media_panel) ScrollView mediaPanel;

    @InjectView(R.id.art) ImageView mediaArt;
    @InjectView(R.id.poster) ImageView mediaPoster;

    @InjectView(R.id.media_title) TextView mediaTitle;
    @InjectView(R.id.media_undertitle) TextView mediaUndertitle;

    @InjectView(R.id.year) TextView mediaYear;
    @InjectView(R.id.genres) TextView mediaGenres;

    @InjectView(R.id.media_description) TextView mediaDescription;

    /**
     * Create a new instance of this, initialized to show the video musicVideoId
     */
    public static MusicVideoDetailsFragment newInstance(int musicVideoId) {
        MusicVideoDetailsFragment fragment = new MusicVideoDetailsFragment();

        Bundle args = new Bundle();
        args.putInt(MUSICVIDEOID, musicVideoId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        musicVideoId = getArguments().getInt(MUSICVIDEOID, -1);

        if ((container == null) || (musicVideoId == -1)) {
            // We're not being shown or there's nothing to show
            return null;
        }

        ViewGroup root = (ViewGroup) inflater.inflate(R.layout.fragment_music_video_details, container, false);
        ButterKnife.inject(this, root);

        bus = EventBus.getDefault();
        hostManager = HostManager.getInstance(getActivity());
        hostInfo = hostManager.getHostInfo();

        swipeRefreshLayout.setOnRefreshListener(this);
        //UIUtils.setSwipeRefreshLayoutColorScheme(swipeRefreshLayout);

        // Setup dim the fanart when scroll changes. Full dim on 4 * iconSize dp
        Resources resources = getActivity().getResources();
        final int pixelsToTransparent  = 4 * resources.getDimensionPixelSize(R.dimen.default_icon_size);
        mediaPanel.getViewTreeObserver().addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
            @Override
            public void onScrollChanged() {
                float y = mediaPanel.getScrollY();
                float newAlpha = Math.min(1, Math.max(0, 1 - (y / pixelsToTransparent)));
                mediaArt.setAlpha(newAlpha);
            }
        });

        FloatingActionButton fab = (FloatingActionButton)fabButton;
        fab.attachToScrollView((ObservableScrollView) mediaPanel);

        // Pad main content view to overlap with bottom system bar
//        UIUtils.setPaddingForSystemBars(getActivity(), mediaPanel, false, false, true);
//        mediaPanel.setClipToPadding(false);

        return root;
    }

    @Override
    public void onActivityCreated (Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Start the loaders
        getLoaderManager().initLoader(LOADER_MUSIC_VIDEO, null, this);

        setHasOptionsMenu(false);
    }

    @Override
    public void onResume() {
        bus.register(this);
        super.onResume();
    }

    @Override
    public void onPause() {
        bus.unregister(this);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
//        outState.putInt(MUSICVIDEOID, musicVideoId);
    }

    /**
     * Swipe refresh layout callback
     */
    /** {@inheritDoc} */
    @Override
    public void onRefresh () {
        if (hostInfo != null) {
            // Start the syncing process
            Intent syncIntent = new Intent(this.getActivity(), LibrarySyncService.class);
            syncIntent.putExtra(LibrarySyncService.SYNC_ALL_MUSIC_VIDEOS, true);
            getActivity().startService(syncIntent);
        } else {
            swipeRefreshLayout.setRefreshing(false);
            Toast.makeText(getActivity(), R.string.no_xbmc_configured, Toast.LENGTH_SHORT)
                 .show();
        }
    }

    /**
     * Event bus post. Called when the syncing process ended
     *
     * @param event Refreshes data
     */
    public void onEventMainThread(MediaSyncEvent event) {
        if (event.syncType.equals(LibrarySyncService.SYNC_ALL_MUSIC_VIDEOS)) {
            swipeRefreshLayout.setRefreshing(false);
            if (event.status == MediaSyncEvent.STATUS_SUCCESS) {
                getLoaderManager().restartLoader(LOADER_MUSIC_VIDEO, null, this);
                if (showRefreshStatusMessage) {
                    Toast.makeText(getActivity(),
                            R.string.sync_successful, Toast.LENGTH_SHORT)
                         .show();
                }
            } else if (showRefreshStatusMessage) {
                String msg = (event.errorCode == ApiException.API_ERROR) ?
                             String.format(getString(R.string.error_while_syncing), event.errorMessage) :
                             getString(R.string.unable_to_connect_to_xbmc);
                Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show();
            }
            // Reset
            showRefreshStatusMessage = true;
        }
    }

    /**
     * Loader callbacks
     */
    /** {@inheritDoc} */
    @Override
    public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
        Uri uri;
        switch (i) {
            case LOADER_MUSIC_VIDEO:
                uri = MediaContract.MusicVideos.buildMusicVideoUri(hostInfo.getId(), musicVideoId);
                return new CursorLoader(getActivity(), uri,
                        MusicVideoDetailsQuery.PROJECTION, null, null, null);
            default:
                return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
        if (cursor != null && cursor.getCount() > 0) {
            switch (cursorLoader.getId()) {
                case LOADER_MUSIC_VIDEO:
                    displayMusicVideoDetails(cursor);
                    break;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onLoaderReset(Loader<Cursor> cursorLoader) {
        // Release loader's data
    }

    /**
     * Callbacks for button bar
     */
    @OnClick(R.id.fab)
    public void onFabClicked(View v) {
        PlaylistType.Item item = new PlaylistType.Item();
        item.musicvideoid = musicVideoId;
        Player.Open action = new Player.Open(item);
        action.execute(hostManager.getConnection(), new ApiCallback<String>() {
            @Override
            public void onSucess(String result) {
                // Do nothing
            }

            @Override
            public void onError(int errorCode, String description) {
                // Got an error, show toast
                Toast.makeText(getActivity(), R.string.unable_to_connect_to_xbmc, Toast.LENGTH_SHORT)
                     .show();
            }
        }, callbackHandler);
    }

    @OnClick(R.id.add_to_playlist)
    public void onAddToPlaylistClicked(View v) {
        Playlist.GetPlaylists getPlaylists = new Playlist.GetPlaylists();

        getPlaylists.execute(hostManager.getConnection(), new ApiCallback<ArrayList<PlaylistType.GetPlaylistsReturnType>>() {
            @Override
            public void onSucess(ArrayList<PlaylistType.GetPlaylistsReturnType> result) {
                // Ok, loop through the playlists, looking for the video one
                int videoPlaylistId = -1;
                for (PlaylistType.GetPlaylistsReturnType playlist : result) {
                    if (playlist.type.equals(PlaylistType.GetPlaylistsReturnType.VIDEO)) {
                        videoPlaylistId = playlist.playlistid;
                        break;
                    }
                }
                // If found, add to playlist
                if (videoPlaylistId != -1) {
                    PlaylistType.Item item = new PlaylistType.Item();
                    item.musicvideoid = musicVideoId;
                    Playlist.Add action = new Playlist.Add(videoPlaylistId, item);
                    action.execute(hostManager.getConnection(), new ApiCallback<String>() {
                        @Override
                        public void onSucess(String result) {
                            if (isAdded()) {
                                // Got an error, show toast
                                Toast.makeText(getActivity(), R.string.item_added_to_playlist, Toast.LENGTH_SHORT)
                                     .show();
                            }
                        }

                        @Override
                        public void onError(int errorCode, String description) {
                            if (isAdded()) {
                                // Got an error, show toast
                                Toast.makeText(getActivity(), R.string.unable_to_connect_to_xbmc, Toast.LENGTH_SHORT)
                                     .show();
                            }
                        }
                    }, callbackHandler);
                } else {
                    if (isAdded()) {
                        Toast.makeText(getActivity(), R.string.no_suitable_playlist, Toast.LENGTH_SHORT)
                             .show();
                    }
                }
            }

            @Override
            public void onError(int errorCode, String description) {
                if (isAdded()) {
                    // Got an error, show toast
                    Toast.makeText(getActivity(), R.string.unable_to_connect_to_xbmc, Toast.LENGTH_SHORT)
                         .show();
                }
            }
        }, callbackHandler);
    }

    @OnClick(R.id.download)
    public void onDownloadClicked(View v) {
        if (musicVideoDownloadInfo == null) {
            // Nothing to download
            Toast.makeText(getActivity(), R.string.no_files_to_download, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if the directory exists and whether to overwrite it
        File file = new File(musicVideoDownloadInfo.getAbsoluteFilePath());
        if (file.exists()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle(R.string.download)
                   .setMessage(R.string.download_file_exists)
                   .setPositiveButton(R.string.overwrite,
                           new DialogInterface.OnClickListener() {
                               @Override
                               public void onClick(DialogInterface dialog, int which) {
                                   FileDownloadHelper.downloadFiles(getActivity(), hostInfo,
                                           musicVideoDownloadInfo, FileDownloadHelper.OVERWRITE_FILES,
                                           callbackHandler);
                               }
                           })
                   .setNeutralButton(R.string.download_with_new_name,
                           new DialogInterface.OnClickListener() {
                               @Override
                               public void onClick(DialogInterface dialog, int which) {
                                   FileDownloadHelper.downloadFiles(getActivity(), hostInfo,
                                           musicVideoDownloadInfo, FileDownloadHelper.DOWNLOAD_WITH_NEW_NAME,
                                           callbackHandler);
                               }
                           })
                   .setNegativeButton(android.R.string.cancel,
                           new DialogInterface.OnClickListener() {
                               @Override
                               public void onClick(DialogInterface dialog, int which) {
                                   // Nothing to do
                               }
                           })
                   .show();
        } else {
            FileDownloadHelper.downloadFiles(getActivity(), hostInfo,
                    musicVideoDownloadInfo, FileDownloadHelper.DOWNLOAD_WITH_NEW_NAME,
                    callbackHandler);
        }
    }

    /**
     * Display the video details
     *
     * @param cursor Cursor with the data
     */
    private void displayMusicVideoDetails(Cursor cursor) {
        cursor.moveToFirst();
        String musicVideoTitle = cursor.getString(MusicVideoDetailsQuery.TITLE);
        mediaTitle.setText(musicVideoTitle);
        String artistAlbum = cursor.getString(MusicVideoDetailsQuery.ARTIST) + "  |  " +
                             cursor.getString(MusicVideoDetailsQuery.ALBUM);
        mediaUndertitle.setText(artistAlbum);

        int runtime = cursor.getInt(MusicVideoDetailsQuery.RUNTIME);
        String durationYear =  runtime > 0 ?
                               UIUtils.formatTime(runtime) + "  |  " +
                               String.valueOf(cursor.getInt(MusicVideoDetailsQuery.YEAR)) :
                               String.valueOf(cursor.getInt(MusicVideoDetailsQuery.YEAR));
        mediaYear.setText(durationYear);
        mediaGenres.setText(cursor.getString(MusicVideoDetailsQuery.GENRES));

        mediaDescription.setText(cursor.getString(MusicVideoDetailsQuery.PLOT));

        // Images
        Resources resources = getActivity().getResources();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        String fanart = cursor.getString(MusicVideoDetailsQuery.FANART),
                poster = cursor.getString(MusicVideoDetailsQuery.THUMBNAIL);

        int artHeight = resources.getDimensionPixelOffset(R.dimen.now_playing_art_height),
                artWidth = displayMetrics.widthPixels;
        if (!TextUtils.isEmpty(fanart)) {
            int posterWidth = resources.getDimensionPixelOffset(R.dimen.musicvideodetail_poster_width);
            int posterHeight = resources.getDimensionPixelOffset(R.dimen.musicvideodetail_poster_heigth);
            mediaPoster.setVisibility(View.VISIBLE);
            UIUtils.loadImageWithCharacterAvatar(getActivity(), hostManager,
                    poster, musicVideoTitle,
                    mediaPoster, posterWidth, posterHeight);
            UIUtils.loadImageIntoImageview(hostManager,
                    fanart,
                    mediaArt, artWidth, artHeight);
        } else {
            // No fanart, just present the poster
            mediaPoster.setVisibility(View.GONE);
            UIUtils.loadImageIntoImageview(hostManager,
                    poster,
                    mediaArt, artWidth, artHeight);
            // Reset padding
            int paddingLeft = mediaTitle.getPaddingRight(),
                    paddingRight = mediaTitle.getPaddingRight(),
                    paddingTop = mediaTitle.getPaddingTop(),
                    paddingBottom = mediaTitle.getPaddingBottom();
            mediaTitle.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
            mediaUndertitle.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
        }





        // Setup download info
        musicVideoDownloadInfo = new FileDownloadHelper.MusicVideoInfo(
                musicVideoTitle, cursor.getString(MusicVideoDetailsQuery.FILE));

        // Check if downloaded file exists
        downloadButton.setVisibility(View.VISIBLE);
        if (musicVideoDownloadInfo.downloadFileExists()) {
            Resources.Theme theme = getActivity().getTheme();
            TypedArray styledAttributes = theme.obtainStyledAttributes(new int[]{
                    R.attr.colorAccent});
            downloadButton.setColorFilter(
                    styledAttributes.getColor(0, R.color.accent_default));
            styledAttributes.recycle();
        } else {
            downloadButton.clearColorFilter();
        }
    }

    /**
     * Video details query parameters.
     */
    private interface MusicVideoDetailsQuery {
        String[] PROJECTION = {
                BaseColumns._ID,
                MediaContract.MusicVideos.TITLE,
                MediaContract.MusicVideos.ALBUM,
                MediaContract.MusicVideos.ARTIST,
                MediaContract.MusicVideos.THUMBNAIL,
                MediaContract.MusicVideos.FANART,
                MediaContract.MusicVideos.YEAR,
                MediaContract.MusicVideos.GENRES,
                MediaContract.MusicVideos.RUNTIME,
                MediaContract.MusicVideos.PLOT,
                MediaContract.MusicVideos.FILE,
        };

        final int ID = 0;
        final int TITLE = 1;
        final int ALBUM = 2;
        final int ARTIST = 3;
        final int THUMBNAIL =4;
        final int FANART = 5;
        final int YEAR = 6;
        final int GENRES = 7;
        final int RUNTIME = 8;
        final int PLOT = 9;
        final int FILE = 10;
    }
}
