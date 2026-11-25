package com.archos.mediacenter.video.leanback.details;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Own copy of PhoneVideoDetailsFragment for the "TV layout page 2" (VideoDetailsFragment for the phone page). The TV screen adds, replaces and removes rows while the details,
 * subtitles, trailers, posters... load, which sends the list back to the top. Here, once the user has scrolled
 * down, the list is put back on the row they were on after each of these updates.
 */
public class PhoneVideoDetails2Fragment extends VideoDetailsFragment {

    private boolean mUserScrolled;
    private int mUserRow;
    private VerticalGridView mGrid;
    private boolean mRestorePosted;
    private ObjectAdapter mObservedAdapter;

    private final ObjectAdapter.DataObserver mObserver = new ObjectAdapter.DataObserver() {
        @Override public void onChanged() { restoreSoon(); }
        @Override public void onItemRangeChanged(int positionStart, int itemCount) { restoreSoon(); }
        @Override public void onItemRangeInserted(int positionStart, int itemCount) { restoreSoon(); }
        @Override public void onItemRangeRemoved(int positionStart, int itemCount) { restoreSoon(); }
        @Override public void onItemMoved(int fromPosition, int toPosition) { restoreSoon(); }
    };

    @Override
    public void setAdapter(ObjectAdapter adapter) {
        super.setAdapter(adapter);
        if (adapter != null && adapter != mObservedAdapter) {
            mObservedAdapter = adapter;
            adapter.registerObserver(mObserver);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        attachScrollTracking(view, 0);
    }

    private void attachScrollTracking(final View root, final int attempt) {
        VerticalGridView grid = getRowsSupportFragment() != null ? getRowsSupportFragment().getVerticalGridView() : null;
        if (grid == null) {
            if (attempt < 20) root.postDelayed(() -> attachScrollTracking(root, attempt + 1), 100);
            return;
        }
        mGrid = grid;
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) mUserScrolled = true;
            }
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (mUserScrolled) mUserRow = mGrid.getSelectedPosition();
            }
        });
    }

    private void restoreSoon() {
        if (mGrid == null || !mUserScrolled || mUserRow <= 0 || mRestorePosted) return;
        mRestorePosted = true;
        mGrid.postDelayed(() -> {
            mRestorePosted = false;
            if (mGrid != null && mUserScrolled && mGrid.getSelectedPosition() < mUserRow
                    && mGrid.getAdapter() != null && mUserRow < mGrid.getAdapter().getItemCount()) {
                mGrid.setSelectedPosition(mUserRow);
            }
        }, 50);
    }
}
