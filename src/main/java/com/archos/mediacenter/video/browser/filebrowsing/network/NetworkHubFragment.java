package com.archos.mediacenter.video.browser.filebrowsing.network;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.BrowserCategory;
import com.archos.mediacenter.video.browser.dlna.DlnaCastSession;
import com.archos.mediacenter.video.browser.dlna.DlnaNowPlaying;
import com.archos.mediacenter.video.browser.dlna.DlnaRendererControl;
import com.archos.mediacenter.video.browser.dlna.DlnaServerFragment;
import com.archos.mediacenter.video.browser.dlna.DlnaServerPrefs;
import com.archos.mediacenter.video.browser.dlna.DlnaServerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Page opened by the "Network" card: shared folders, media servers, network, cloud and the DLNA host. */
public class NetworkHubFragment extends Fragment {
    private static final class Entry {
        final int title;
        final int icon;
        final int color;
        final int sourceText;   // string id of the BrowserCategory entry to open, or 0 for the DLNA host
        Entry(int title, int icon, int color, int sourceText) {
            this.title = title;
            this.icon = icon;
            this.color = color;
            this.sourceText = sourceText;
        }
    }

    private static final int SOURCE_NOW = -1;      // the "DLNA Now" tile
    private static final int TYPE_CARD = 0;
    private static final int TYPE_HEADER = 1;
    private static final int TYPE_SOURCE = 2;
    private static final int TYPE_NOW = 3;

    /** Entry (fixed cards), a String (section heading), BrowserCategory.ItemData (a discovered source) or a DlnaNowPlaying.Stream. */
    private final List<Object> mEntries = new ArrayList<>();
    private int mSpan = 2;
    private RecyclerView mList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        rebuildEntries();

        mList = new RecyclerView(requireContext());
        float density = getResources().getDisplayMetrics().density;
        int span = Math.max(2, (int) (getResources().getDisplayMetrics().widthPixels / (114 * density)));
        mSpan = span;
        GridLayoutManager layout = new GridLayoutManager(requireContext(), span);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                Object o = mEntries.get(position);
                return o instanceof String || o instanceof DlnaNowPlaying.Stream ? mSpan : 1;
            }
        });
        mList.setLayoutManager(layout);
        int pad = Math.round(14 * density);
        mList.setPadding(pad, pad, pad, pad);
        mList.setClipToPadding(false);
        mList.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public int getItemViewType(int position) {
                Object o = mEntries.get(position);
                return o instanceof DlnaNowPlaying.Stream ? TYPE_NOW : o instanceof String ? TYPE_HEADER
                        : o instanceof BrowserCategory.ItemData ? TYPE_SOURCE : TYPE_CARD;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                if (viewType == TYPE_HEADER) return new RecyclerView.ViewHolder(createHeader(parent)) { };
                if (viewType == TYPE_NOW) return createNowCard(parent);
                View card = LayoutInflater.from(parent.getContext()).inflate(R.layout.browser_source_card_phone, parent, false);
                return new RecyclerView.ViewHolder(card) { };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                Object entry = mEntries.get(position);
                View card = holder.itemView;
                if (entry instanceof DlnaNowPlaying.Stream) {
                    bindNow((NowHolder) holder, (DlnaNowPlaying.Stream) entry);
                    return;
                }
                if (entry instanceof String) {
                    ((TextView) card.findViewById(android.R.id.text1)).setText((String) entry);
                    return;
                }
                if (entry instanceof BrowserCategory.ItemData) {
                    bindSource(card, (BrowserCategory.ItemData) entry);
                    return;
                }
                final Entry e = (Entry) entry;
                card.findViewById(R.id.tile_artwork).setBackgroundTintList(ColorStateList.valueOf(e.color));
                ((ImageView) card.findViewById(R.id.tile_icon)).setImageResource(e.icon);
                ((TextView) card.findViewById(R.id.tile_title)).setText(e.title);
                TextView details = card.findViewById(R.id.tile_subtitle);
                CharSequence text = details(e);
                details.setText(text);
                details.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
                card.findViewById(R.id.tile_pin).setVisibility(View.GONE);
                card.setOnClickListener(v -> open(e));
            }

            @Override
            public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
                if (holder instanceof NowHolder && ((NowHolder) holder).client != null) {
                    mNowHolders.remove(((NowHolder) holder).client);
                }
            }

            @Override
            public int getItemCount() {
                return mEntries.size();
            }
        });
        return mList;
    }

    // ------------------------------------------------------------------ "Now playing" DLNA tile

    private static final class NowHolder extends RecyclerView.ViewHolder {
        String client;
        TextView name;
        TextView device;
        TextView timeCurrent;
        TextView timeTotal;
        TextView volume;
        SeekBar seek;
        ImageButton play;
        View[] buttons;
        volatile boolean dragging;
        volatile boolean busy;
        volatile int positionSec = -1;
        volatile int durationSec = -1;
        volatile boolean playing;
        NowHolder(View v) {
            super(v);
        }
    }

    private final Map<String, DlnaRendererControl> mControls = new HashMap<>();
    private final Map<String, NowHolder> mNowHolders = new HashMap<>();
    private boolean mShownNow;
    private final android.os.Handler mUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private ExecutorService mExecutor;
    private ExecutorService mDiscovery;     // searching for a player can take seconds: kept off the control thread
    private int mTick;
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            mTick++;
            if (DlnaCastSession.isActive() != mShownNow) refreshSources();
            mUi.postDelayed(this, 1000);
        }
    };

    private DlnaRendererControl control(String client) {
        DlnaRendererControl c = mControls.get(client);
        if (c == null) {
            c = new DlnaRendererControl(client);
            mControls.put(client, c);
        }
        return c;
    }

    private ImageButton nowButton(ViewGroup parent, int icon, int size) {
        ImageButton b = new ImageButton(parent.getContext());
        b.setImageResource(icon);
        b.setBackgroundResource(R.drawable.mobile_press_circle);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round(10 * parent.getResources().getDisplayMetrics().density);
        b.setPadding(pad, pad, pad, pad);
        b.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        return b;
    }

    private NowHolder createNowCard(ViewGroup parent) {
        final android.content.Context ctx = parent.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * density);
        int size = Math.round(46 * density);
        android.widget.LinearLayout card = new android.widget.LinearLayout(ctx);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF2A2F3A);
        bg.setCornerRadius(16 * density);
        card.setBackground(bg);
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(10 * density);
        card.setLayoutParams(lp);

        TextView heading = new TextView(ctx);
        heading.setText(R.string.dlna_now_playing);
        heading.setTextColor(0xFF9FB3D1);
        heading.setTextSize(12);
        heading.setAllCaps(true);
        card.addView(heading);

        final NowHolder h = new NowHolder(card);
        h.name = new TextView(ctx);
        h.name.setTextColor(0xFFFFFFFF);
        h.name.setTextSize(16);
        h.name.setSingleLine(true);
        h.name.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        h.name.setSelected(true);
        card.addView(h.name);
        h.device = new TextView(ctx);
        h.device.setTextColor(0xB3FFFFFF);
        h.device.setTextSize(12);
        card.addView(h.device);

        android.widget.LinearLayout seekRow = new android.widget.LinearLayout(ctx);
        seekRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        h.timeCurrent = new TextView(ctx);
        h.timeCurrent.setTextColor(0xFFFFFFFF);
        h.timeCurrent.setTextSize(12);
        h.seek = new SeekBar(ctx);
        h.timeTotal = new TextView(ctx);
        h.timeTotal.setTextColor(0xFFFFFFFF);
        h.timeTotal.setTextSize(12);
        seekRow.addView(h.timeCurrent);
        seekRow.addView(h.seek, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seekRow.addView(h.timeTotal);
        card.addView(seekRow);

        android.widget.LinearLayout buttons = new android.widget.LinearLayout(ctx);
        buttons.setGravity(android.view.Gravity.CENTER);
        ImageButton back = nowButton(parent, R.drawable.mobile_ic_rewind, size);
        h.play = nowButton(parent, R.drawable.mobile_ic_play, size);
        ImageButton forward = nowButton(parent, R.drawable.mobile_ic_forward, size);
        ImageButton volDown = nowButton(parent, R.drawable.ic_dlna_volume_down, size);
        ImageButton volUp = nowButton(parent, R.drawable.ic_dlna_volume_up, size);
        h.volume = new TextView(ctx);
        h.volume.setTextColor(0xFFFFFFFF);
        h.volume.setTextSize(12);
        h.volume.setMinWidth(Math.round(34 * density));
        h.volume.setGravity(android.view.Gravity.CENTER);
        buttons.addView(back);
        buttons.addView(h.play);
        buttons.addView(forward);
        android.view.View gap = new View(ctx);
        buttons.addView(gap, new android.widget.LinearLayout.LayoutParams(Math.round(18 * density), 1));
        buttons.addView(volDown);
        buttons.addView(h.volume);
        buttons.addView(volUp);
        card.addView(buttons);
        h.buttons = new View[]{h.seek, back, h.play, forward, volDown, volUp};

        back.setOnClickListener(v -> jump(h, -10));
        forward.setOnClickListener(v -> jump(h, 10));
        h.play.setOnClickListener(v -> {
            final String client = h.client;
            if (client == null) return;
            final boolean wasPlaying = h.playing;
            mExecutor.execute(() -> {
                if (wasPlaying) control(client).pause(); else control(client).play();
                pollOne(client, h);
            });
        });
        volDown.setOnClickListener(v -> changeVolume(h, -5));
        volUp.setOnClickListener(v -> changeVolume(h, 5));
        h.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) h.timeCurrent.setText(formatTime(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                h.dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                final String client = h.client;
                final int target = bar.getProgress();
                if (client != null) {
                    mExecutor.execute(() -> {
                        control(client).seekTo(target);
                        h.dragging = false;
                        pollOne(client, h);
                    });
                } else {
                    h.dragging = false;
                }
            }
        });
        return h;
    }

    private void jump(NowHolder h, int deltaSec) {
        final String client = h.client;
        if (client == null || h.positionSec < 0) return;
        int target = Math.max(0, h.positionSec + deltaSec);
        if (h.durationSec > 0) target = Math.min(target, h.durationSec);
        final int seconds = target;
        mExecutor.execute(() -> {
            control(client).seekTo(seconds);
            pollOne(client, h);
        });
    }

    private void changeVolume(final NowHolder h, final int delta) {
        final String client = h.client;
        if (client == null) return;
        mExecutor.execute(() -> {
            final int volume = control(client).changeVolume(delta);
            mUi.post(() -> h.volume.setText(volume < 0 ? "" : String.valueOf(volume)));
        });
    }

    private void bindNow(NowHolder h, DlnaNowPlaying.Stream stream) {
        h.client = stream.client;
        mNowHolders.put(stream.client, h);
        h.name.setText(stream.title);
        h.device.setText(getString(R.string.dlna_now_playing_searching, stream.client));
        h.timeCurrent.setText("--:--");
        h.timeTotal.setText("--:--");
        h.seek.setEnabled(false);
        for (View b : h.buttons) b.setAlpha(0.45f);
        pollOne(stream.client, h);
    }

    private void pollOne(final String client, final NowHolder h) {
        if (mExecutor == null || mExecutor.isShutdown() || h.busy) return;
        final DlnaRendererControl first = control(client);
        if (!first.isFound()) {
            // not found yet: search on the other thread so the buttons never wait for it
            if (!first.isSearching() && mDiscovery != null && !mDiscovery.isShutdown()) {
                h.busy = true;
                mDiscovery.execute(() -> {
                    first.find();
                    mUi.post(() -> {
                        h.busy = false;
                        if (client.equals(h.client)) applyState(h, first, new DlnaRendererControl.State());
                    });
                });
            }
            return;
        }
        h.busy = true;
        final boolean withVolume = mTick % 3 == 0;
        try {
            mExecutor.execute(() -> {
                final DlnaRendererControl control = control(client);
                final DlnaRendererControl.State state = control.poll(withVolume);
                mUi.post(() -> {
                    h.busy = false;
                    if (!client.equals(h.client)) return;
                    applyState(h, control, state);
                });
            });
        } catch (RuntimeException e) {
            h.busy = false;
        }
    }

    private void applyState(NowHolder h, DlnaRendererControl control, DlnaRendererControl.State state) {
        boolean controllable = control.isFound();
        boolean answers = !state.transport.isEmpty() || state.positionSec >= 0;
        String line;
        if (!controllable) {
            line = control.isSearching() || control.getStatus().isEmpty()
                    ? getString(R.string.dlna_now_playing_searching, h.client)
                    : getString(R.string.dlna_now_playing_no_control, h.client) + " (" + control.getStatus() + ")";
        } else if (!answers) {
            line = control.getName() + " - no reply to the status request"
                    + (control.getLastError().isEmpty() ? "" : " (" + control.getLastError() + ")");
        } else {
            line = control.getName() + (state.transport.isEmpty() ? "" : " - " + state.transport.toLowerCase(java.util.Locale.ROOT));
        }
        h.device.setText(line);
        for (View b : h.buttons) b.setAlpha(controllable ? 1f : 0.45f);
        h.seek.setEnabled(controllable);
        h.playing = "PLAYING".equals(state.transport);
        h.play.setImageResource(h.playing ? R.drawable.mobile_ic_pause : R.drawable.mobile_ic_play);
        int duration = state.durationSec;
        for (DlnaNowPlaying.Stream s : DlnaNowPlaying.active()) {
            if (s.client.equals(h.client) && duration <= 0 && s.durationUs > 0) duration = (int) (s.durationUs / 1_000_000);
        }
        h.positionSec = state.positionSec;
        h.durationSec = duration;
        if (duration > 0) h.seek.setMax(duration);
        if (!h.dragging && state.positionSec >= 0) {
            h.seek.setProgress(state.positionSec);
            h.timeCurrent.setText(formatTime(state.positionSec));
        }
        h.timeTotal.setText(duration > 0 ? formatTime(duration) : "--:--");
        if (state.volume >= 0) h.volume.setText(String.valueOf(state.volume));
    }

    private static String formatTime(int seconds) {
        return seconds >= 3600
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private BrowserCategory category() {
        if (getActivity() == null) return null;
        Fragment f = getActivity().getSupportFragmentManager().findFragmentById(R.id.category);
        return f instanceof BrowserCategory ? (BrowserCategory) f : null;
    }

    private void rebuildEntries() {
        mEntries.clear();
        mShownNow = DlnaCastSession.isActive();
        // "DLNA Now" comes first: it opens the page of the video cast to a TV
        if (mShownNow) mEntries.add(new Entry(R.string.dlna_now_title, R.drawable.ic_dlna_server, 0xFFB5451B, SOURCE_NOW));
        mEntries.add(new Entry(R.string.network_shared_folders, R.drawable.category_common_network, 0xFF8A4B5C, R.string.network_shared_folders));
        mEntries.add(new Entry(R.string.network_media_servers, R.drawable.category_common_network, 0xFF2F7D6B, R.string.network_media_servers));
        mEntries.add(new Entry(R.string.network_shortcuts, R.drawable.category_network_shortcut, 0xFF4A6A2F, R.string.network_shortcuts));
        mEntries.add(new Entry(R.string.provider_folders, R.drawable.cloud_24px, 0xFF4B5563, R.string.provider_folders));
        mEntries.add(new Entry(R.string.dlna_server_title, R.drawable.ic_dlna_server, 0xFF6B4C93, 0));
        BrowserCategory category = category();
        List<BrowserCategory.ItemData> found = category == null
                ? new ArrayList<>() : category.getDiscoveredNetworkSources();
        // divider + the sources found on the network (they used to be cards of the Select Source row)
        mEntries.add(getString(found.isEmpty() ? R.string.home_available_sources_none : R.string.home_available_sources));
        mEntries.addAll(found);
    }

    private View createHeader(ViewGroup parent) {
        float density = parent.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(parent.getContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View divider = new View(parent.getContext());
        divider.setBackgroundColor(0x33FFFFFF);
        box.addView(divider, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density))));
        TextView title = new TextView(parent.getContext());
        title.setId(android.R.id.text1);
        title.setTextColor(0xFF9FB3D1);
        title.setTextSize(13);
        title.setAllCaps(true);
        title.setPadding(0, Math.round(12 * density), 0, Math.round(6 * density));
        box.addView(title);
        return box;
    }

    private void bindSource(View card, final BrowserCategory.ItemData item) {
        final BrowserCategory category = category();
        card.findViewById(R.id.tile_artwork).setBackgroundTintList(
                ColorStateList.valueOf(category == null ? 0xFF34465F : category.getSourceColor(item)));
        ImageView icon = card.findViewById(R.id.tile_icon);
        icon.setVisibility(item.icon != -1 ? View.VISIBLE : View.GONE);
        if (item.icon != -1) icon.setImageResource(item.icon);
        ((TextView) card.findViewById(R.id.tile_title)).setText(category == null ? item.name : category.getSourceTitle(item));
        TextView details = card.findViewById(R.id.tile_subtitle);
        CharSequence text = category == null ? "" : category.getSourceDetails(item);
        details.setText(text);
        details.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
        card.findViewById(R.id.tile_pin).setVisibility(View.GONE);
        card.setOnClickListener(v -> {
            BrowserCategory c = category();
            if (c != null) c.openDiscoveredSource(item);
        });
    }

    private void refreshSources() {
        if (mList == null || mList.getAdapter() == null) return;
        rebuildEntries();
        mList.getAdapter().notifyDataSetChanged();
    }

    /** Opens a discovered network source (smb:// share server or upnp:// media server) as a browsing page. */
    public static void openShare(BrowserCategory category, android.net.Uri uri, String name) {
        openShare(category, uri, name, false);
    }

    /** @param firstPage true when opened from the home screen: it becomes the base page, Back returns home. */
    public static void openShare(BrowserCategory category, android.net.Uri uri, String name, boolean firstPage) {
        Bundle args = new Bundle();
        args.putParcelable(BrowserByNetwork.CURRENT_DIRECTORY, uri);
        args.putString(BrowserByNetwork.TITLE, name);
        args.putString(BrowserByNetwork.SHARE_NAME, com.archos.filecorelibrary.FileUtils.getName(uri));
        Fragment f = "smb".equals(uri.getScheme())
                ? new com.archos.mediacenter.video.browser.filebrowsing.network.SmbBrowser.BrowserBySmb()
                : new com.archos.mediacenter.video.browser.filebrowsing.network.UpnpBrowser.BrowserByUpnp();
        f.setArguments(args);
        if (firstPage) category.loadFragmentAfterStackReset(f);
        else category.startContent(f);
    }

    private CharSequence details(Entry e) {
        // (resource ids are not constants, so no switch)
        if (e.sourceText == SOURCE_NOW) return DlnaCastSession.getTitle();
        if (e.title == R.string.network_shared_folders) return getString(R.string.home_sub_smb);
        if (e.title == R.string.network_media_servers) return getString(R.string.home_sub_upnp);
        if (e.title == R.string.network_shortcuts) return getString(R.string.home_sub_shortcuts);
        if (e.title == R.string.provider_folders) return getString(R.string.home_sub_provider);
        if (DlnaServerService.isRunning()) return getString(R.string.dlna_card_running);
        int folders = new DlnaServerPrefs(requireContext()).getFolders().size();
        return folders == 0 ? getString(R.string.dlna_card_stopped)
                : getResources().getQuantityString(R.plurals.dlna_card_folders, folders, folders);
    }

    private void open(Entry e) {
        if (getActivity() == null) return;
        openEntry(requireActivity(), e.title, e.sourceText);
    }

    /** Opens one of the fixed network pages; also used by the Files page. sourceText 0 is the DLNA host. */
    public static void openEntry(androidx.fragment.app.FragmentActivity activity, int title, int sourceText) {
        final Entry e = new Entry(title, 0, 0, sourceText);
        if (e.sourceText == SOURCE_NOW) {
            com.archos.mediacenter.video.browser.dlna.DlnaCastActivity.open(activity);
            return;
        }
        Fragment category = activity.getSupportFragmentManager().findFragmentById(R.id.category);
        if (!(category instanceof BrowserCategory)) return;
        BrowserCategory c = (BrowserCategory) category;
        // Pages are pushed on the back stack (not started as a new category), so Back returns to this
        // page and then to the home screen.
        Fragment page = null;
        if (e.sourceText == 0) page = new DlnaServerFragment();
        else if (e.sourceText == R.string.network_shared_folders) page = new com.archos.mediacenter.video.browser.filebrowsing.network.SmbBrowser.SmbRootFragment();
        else if (e.sourceText == R.string.network_media_servers) page = new com.archos.mediacenter.video.browser.filebrowsing.network.UpnpBrowser.UpnpRootFragment();
        else if (e.sourceText == R.string.network_shortcuts) page = new ShortcutRootFragment();
        if (page != null) {
            c.startContent(page);
            if (e.sourceText != 0 && activity instanceof AppCompatActivity
                    && ((AppCompatActivity) activity).getSupportActionBar() != null) {
                ((AppCompatActivity) activity).getSupportActionBar().setTitle(e.title);
            }
        } else {
            c.openSourceByTitle(e.sourceText);      // cloud: opens the system file chooser
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mExecutor == null || mExecutor.isShutdown()) mExecutor = Executors.newSingleThreadExecutor();
        if (mDiscovery == null || mDiscovery.isShutdown()) mDiscovery = Executors.newSingleThreadExecutor();
        DlnaCastSession.setListener(() -> mUi.post(this::refreshSources));
        mUi.removeCallbacks(mPoll);
        mUi.postDelayed(mPoll, 1000);
        if (getActivity() instanceof AppCompatActivity && ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.home_network);
        }
        BrowserCategory category = category();
        if (category != null) category.setNetworkSourcesListener(this::refreshSources);
        refreshSources();   // also refreshes the DLNA status
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mUi.removeCallbacks(mPoll);
        mNowHolders.clear();
        if (mExecutor != null) mExecutor.shutdownNow();
        if (mDiscovery != null) mDiscovery.shutdownNow();
    }

    @Override
    public void onPause() {
        super.onPause();
        DlnaCastSession.setListener(null);
        mUi.removeCallbacks(mPoll);
        BrowserCategory category = category();
        if (category != null) category.setNetworkSourcesListener(null);
    }
}
