package com.archos.mediacenter.video.browser.dlna;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.archos.mediacenter.video.R;

import java.util.List;

/** Settings page of the DLNA host: start / stop, name, auto start and the shared folders. */
public class DlnaServerFragment extends Fragment {
    private DlnaServerPrefs mPrefs;
    private SwitchCompat mSwitch;
    private TextView mStatus;
    private TextView mName;
    private LinearLayout mFolderList;
    private boolean mBinding;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            mHandler.postDelayed(this, 1000);
        }
    };

    private final ActivityResultLauncher<Uri> mPickFolder = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        mPrefs = new DlnaServerPrefs(requireContext());
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(root);

        // Start / stop
        mSwitch = new SwitchCompat(requireContext());
        mSwitch.setText(R.string.dlna_server_title);
        mSwitch.setTextSize(18);
        mSwitch.setMinHeight(dp(56));
        mSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mBinding) return;
            if (checked) {
                if (mPrefs.getFolders().isEmpty()) {
                    Toast.makeText(requireContext(), R.string.dlna_add_folder_first, Toast.LENGTH_LONG).show();
                }
                mPrefs.setRunningRequested(true);
                DlnaServerService.start(requireContext());
            } else {
                mPrefs.setRunningRequested(false);
                DlnaServerService.stop(requireContext());
            }
        });
        root.addView(mSwitch);

        mStatus = new TextView(requireContext());
        mStatus.setTextSize(13);
        mStatus.setAlpha(0.75f);
        root.addView(mStatus);

        // Name
        root.addView(sectionTitle(R.string.dlna_settings));
        mName = row(getString(R.string.dlna_server_name), mPrefs.getName());
        mName.setOnClickListener(v -> editName());
        root.addView(mName);

        CheckBox auto = new CheckBox(requireContext());
        auto.setText(R.string.dlna_auto_start);
        auto.setChecked(mPrefs.isAutoStart());
        auto.setOnCheckedChangeListener((b, checked) -> mPrefs.setAutoStart(checked));
        root.addView(auto);

        // Folders
        root.addView(sectionTitle(R.string.dlna_shared_folders));
        mFolderList = new LinearLayout(requireContext());
        mFolderList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mFolderList);

        Button add = new Button(requireContext());
        add.setText(R.string.dlna_add_folder);
        add.setOnClickListener(v -> {
            try {
                mPickFolder.launch(null);
            } catch (RuntimeException e) {
                Toast.makeText(requireContext(), R.string.dlna_no_folder_picker, Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = dp(8);
        root.addView(add, addParams);

        TextView hint = new TextView(requireContext());
        hint.setText(R.string.dlna_hint);
        hint.setTextSize(12);
        hint.setAlpha(0.65f);
        hint.setPadding(0, dp(16), 0, 0);
        root.addView(hint);

        rebuildFolders();
        return scroll;
    }

    private TextView sectionTitle(int text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextSize(13);
        t.setAllCaps(true);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(24), 0, dp(4));
        return t;
    }

    private TextView row(String title, String value) {
        TextView t = new TextView(requireContext());
        t.setTextSize(16);
        t.setMinHeight(dp(56));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setText(title + "\n" + value);
        t.setClickable(true);
        t.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        t.setBackgroundResource(tv.resourceId);
        return t;
    }

    private void editName() {
        final EditText input = new EditText(requireContext());
        input.setText(mPrefs.getName());
        input.setSingleLine();
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dlna_server_name)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        mPrefs.setName(name);
                        mName.setText(getString(R.string.dlna_server_name) + "\n" + name);
                        if (DlnaServerService.isRunning()) {          // announce the new name
                            DlnaServerService.stop(requireContext());
                            mHandler.postDelayed(() -> DlnaServerService.start(requireContext()), 800);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onFolderPicked(Uri tree) {
        if (tree == null) return;
        try {
            requireContext().getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // still readable in this session
        }
        String name = tree.getLastPathSegment();
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            if (name.isEmpty()) name = id;
            int slash = name.lastIndexOf('/');
            if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        } catch (RuntimeException ignored) {
            // keep the segment
        }
        if (mPrefs.addFolder(tree, name == null ? "Folder" : name)) {
            rebuildFolders();
        } else {
            Toast.makeText(requireContext(), R.string.dlna_folder_already, Toast.LENGTH_SHORT).show();
        }
    }

    private void rebuildFolders() {
        mFolderList.removeAllViews();
        List<DlnaServerPrefs.Folder> folders = mPrefs.getFolders();
        if (folders.isEmpty()) {
            TextView none = new TextView(requireContext());
            none.setText(R.string.dlna_no_folders);
            none.setAlpha(0.65f);
            none.setMinHeight(dp(48));
            none.setGravity(Gravity.CENTER_VERTICAL);
            mFolderList.addView(none);
            return;
        }
        for (int i = 0; i < folders.size(); i++) {
            final int index = i;
            LinearLayout line = new LinearLayout(requireContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(requireContext());
            name.setText(folders.get(i).name);
            name.setTextSize(16);
            line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton remove = new ImageButton(requireContext());
            remove.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            remove.setBackgroundResource(0);
            remove.setContentDescription(getString(R.string.dlna_remove_folder));
            remove.setOnClickListener(v -> {
                mPrefs.removeFolder(index);
                rebuildFolders();
            });
            line.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
            line.setMinimumHeight(dp(48));
            mFolderList.addView(line);
        }
    }

    private void refreshStatus() {
        if (mSwitch == null) return;
        boolean running = DlnaServerService.isRunning();
        mBinding = true;
        mSwitch.setChecked(running || (mPrefs.isRunningRequested() && DlnaServerService.getError() == null));
        mBinding = false;
        String error = DlnaServerService.getError();
        if (running) mStatus.setText(getString(R.string.dlna_status_running, DlnaServerService.getUrl()));
        else if (error != null) mStatus.setText(getString(R.string.dlna_status_error, error));
        else if (mPrefs.isRunningRequested()) mStatus.setText(R.string.dlna_status_starting);
        else mStatus.setText(R.string.dlna_status_stopped);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof AppCompatActivity && ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.dlna_server_title);
        }
        mHandler.post(mRefresh);
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mRefresh);
        super.onPause();
    }
}
