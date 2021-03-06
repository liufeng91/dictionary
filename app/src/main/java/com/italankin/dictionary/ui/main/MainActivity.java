/*
 * Copyright 2016 Igor Talankin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.italankin.dictionary.ui.main;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Size;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ShareCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.italankin.dictionary.App;
import com.italankin.dictionary.R;
import com.italankin.dictionary.dto.Language;
import com.italankin.dictionary.dto.Result;
import com.italankin.dictionary.dto.TranslationEx;
import com.italankin.dictionary.ui.settings.SettingsActivity;
import com.italankin.dictionary.ui.translation.TranslationActivity;
import com.italankin.dictionary.utils.SharedPrefs;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.OnFocusChange;
import butterknife.OnLongClick;

import static android.widget.AdapterView.OnItemSelectedListener;

public class MainActivity extends AppCompatActivity {

    private static final int SWITCH_ANIM_DURATION = 450;
    private static final int SWAP_LANGS_ANIM_DURATION = 300;
    private static final long PROGRESS_ANIM_DURATION = 300;
    private static final int TOOLBAR_ANIM_IN_DURATION = 600;
    private static final int SHARE_FAB_ANIM_DURATION = 300;
    private static final float INPUT_SCROLL_PARALLAX_FACTOR = 1.2f;
    private static final int MAX_INTENT_TEXT_LENGTH = 60;

    private static final int REQUEST_CODE_SHARE = 17;

    private static final String KEY_LAST_RESULT = "last_result";
    private static final String KEY_HISTORY = "history";

    @Inject
    MainPresenter _presenter;

    @Inject
    SharedPrefs _prefs;

    //region Views
    @BindView(R.id.toolbar)
    Toolbar toolbar;

    /**
     * EditText for text input
     */
    @BindView(R.id.edit_input)
    EditText mInput;

    @BindView(R.id.input_card)
    View mInputLayout;

    /**
     * TextView in toolbar indicating current destination language
     */
    @BindView(R.id.spinner_lang_source)
    Spinner mSpinnerSource;

    /**
     * TextView in toolbar indicating current source language
     */
    @BindView(R.id.spinner_lang_dest)
    Spinner mSpinnerDest;

    /**
     * Arrow in the toolbar for switching languages
     */
    @BindView(R.id.swap_langs)
    View mSwapLangs;

    @BindView(R.id.lookup)
    View mLookup;

    @BindView(R.id.text_transcription)
    TextView mTranscription;

    @BindView(R.id.toolbar_inner_layout)
    View mToolbarInnerLayout;

    @BindView(R.id.recycler_view)
    RecyclerView mRecyclerView;

    @BindView(R.id.progress_bar)
    ProgressBar mProgressBar;

    @BindView(R.id.btn_share)
    FloatingActionButton mShareFab;
    //endregion

    private InputMethodManager mInputManager;
    private ClipboardManager mClipboardManager;

    private TranslationAdapter mRecyclerViewAdapter;

    private Result mLastResult;
    private ArrayList<String> mHistory = new ArrayList<>(0);

    ///////////////////////////////////////////////////////////////////////////
    // Activity callbacks
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        App.injector().inject(this);
        _presenter.attach(this);

        if (savedInstanceState != null) {
            mLastResult = savedInstanceState.getParcelable(KEY_LAST_RESULT);
            mHistory = savedInstanceState.getStringArrayList(KEY_HISTORY);
        }

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        mInputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        mClipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        setSupportActionBar(toolbar);
        setupInputLayout();
        setupRecyclerView();
        setControlsState(false);

        _presenter.loadLanguages();
    }

    private void setupRecyclerView() {
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        Resources res = getResources();
        int left = res.getDimensionPixelSize(R.dimen.list_item_margin_left);
        int top = res.getDimensionPixelSize(R.dimen.list_item_margin_top);
        int right = res.getDimensionPixelSize(R.dimen.list_item_margin_right);
        int bottom = res.getDimensionPixelSize(R.dimen.list_item_margin_bottom);
        mRecyclerView.addItemDecoration(new SimpleItemDecoration(left, top, right, bottom));

        // allow input layout to be scrolled along with recycler view
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateInputLayoutPosition();
            }
        });

        top = res.getDimensionPixelSize(R.dimen.list_top_offset);
        int inputHeight = res.getDimensionPixelSize(R.dimen.input_panel_height);
        mRecyclerView.setPadding(
                mRecyclerView.getPaddingLeft(),
                inputHeight + top,
                mRecyclerView.getPaddingRight(),
                mRecyclerView.getPaddingBottom()
        );

        // on click listener

        mRecyclerViewAdapter = new TranslationAdapter(this);
        mRecyclerViewAdapter.setHasStableIds(true);
        mRecyclerViewAdapter.setListener(new TranslationAdapter.OnAdapterItemClickListener() {
            @Override
            public void onItemClick(int position) {
                TranslationEx item = mLastResult.translations.get(position);
                startActivity(TranslationActivity.getStartIntent(getApplicationContext(), item));
            }

            @Override
            public void onItemMenuClick(int position, int menuItemId) {
                ClipData clip = null;
                TranslationEx item = mLastResult.translations.get(position);
                switch (menuItemId) {
                    case R.id.action_lookup_word:
                        startLookup(item.text);
                        break;

                    case R.id.action_copy_mean:
                        clip = ClipData.newPlainText("means", item.means);
                        break;

                    case R.id.action_copy_synonyms:
                        clip = ClipData.newPlainText("synonyms", item.synonyms);
                        break;

                    case R.id.action_copy_translation:
                        clip = ClipData.newPlainText("translation", item.text);
                        break;
                }
                if (clip != null) {
                    mClipboardManager.setPrimaryClip(clip);
                    Toast.makeText(MainActivity.this, R.string.msg_copied, Toast.LENGTH_SHORT).show();
                }
            }
        });
        mRecyclerViewAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateInputLayoutPosition();
            }
        });

        mRecyclerView.setAdapter(mRecyclerViewAdapter);
    }

    private void setupInputLayout() {
        mInputLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mInput.requestFocus();
                mInputManager.showSoftInput(mInput, 0);
            }
        });
        ViewTreeObserver vto = mInputLayout.getViewTreeObserver();
        // add a global layout listener for updating input layout position for the first time
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver vto = mInputLayout.getViewTreeObserver();
                if (Build.VERSION.SDK_INT >= 16) {
                    vto.removeOnGlobalLayoutListener(this);
                } else {
                    //noinspection deprecation
                    vto.removeGlobalOnLayoutListener(this);
                }
                updateInputLayoutPosition();
            }
        });

        mInput.setImeActionLabel(getString(R.string.lookup), KeyEvent.KEYCODE_ENTER);
        mInput.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                    startLookup();
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        int fabVisibility = mShareFab.getVisibility();
        if (_prefs.showShareFab()) {
            if (mLastResult != null && fabVisibility != View.VISIBLE) {
                showShareFab();
            }
        } else if (fabVisibility != View.GONE) {
            mShareFab.setVisibility(View.GONE);
            mRecyclerViewAdapter.showExtraSpace(false);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mLastResult != null) {
            outState.putParcelable(KEY_LAST_RESULT, mLastResult);
        }
        outState.putStringArrayList(KEY_HISTORY, mHistory);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        _presenter.saveLanguages();
        _presenter.detach();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        handleIntent(intent);
    }

    private boolean handleIntent(Intent intent) {
        if (intent.getType() != null && intent.hasExtra(Intent.EXTRA_TEXT)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            text = text.substring(0, Math.min(text.length(), MAX_INTENT_TEXT_LENGTH));
            if ("text/plain".equals(intent.getType()) && !TextUtils.isEmpty(text)) {
                intent.setType(null);
                mInput.setText(text);
                mInput.selectAll();
                startLookup(text);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SHARE && resultCode == RESULT_OK && _prefs.closeOnShare()) {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (_prefs.backFocusSearch()) {
            if (mInput.hasFocus()) {
                super.onBackPressed();
            } else {
                mInput.requestFocus();
                mInputManager.showSoftInput(mInput, 0);
            }
        } else {
            super.onBackPressed();
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // ButterKnife
    ///////////////////////////////////////////////////////////////////////////

    @OnClick(R.id.lookup)
    void onLookupClick() {
        startLookup();
    }

    @OnFocusChange(R.id.edit_input)
    void onEditInputFocusChange(boolean hasFocus) {
        if (hasFocus) {
            mRecyclerView.smoothScrollToPosition(0);
        }
    }

    @OnLongClick(R.id.text_transcription)
    boolean onTranscriptionLongClick(View v) {
        ClipData clip = ClipData.newPlainText("transcription", mTranscription.getText().toString());
        mClipboardManager.setPrimaryClip(clip);
        Toast.makeText(MainActivity.this, R.string.msg_transcription_copied, Toast.LENGTH_SHORT).show();
        return true;
    }

    @OnClick(R.id.swap_langs)
    void onSwapLangsClick() {
        swapLanguages();
    }

    @OnLongClick(R.id.swap_langs)
    boolean onSwapLangsLongClick() {
        Toast.makeText(MainActivity.this, R.string.toast_swap_langs, Toast.LENGTH_SHORT).show();
        return true;
    }

    @OnClick(R.id.btn_share)
    void onShareFabClick() {
        shareLastResult();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Options menu
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_share:
                shareLastResult();
                return true;

            case R.id.action_history:
                showHistoryDialog();
                return true;

            case R.id.action_settings:
                startActivity(SettingsActivity.getStartIntent(this));
                return true;
        }

        return false;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Views update
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Sync scroll state of the top layout.
     */
    private void updateInputLayoutPosition() {
        float max = mInputLayout.getHeight() * INPUT_SCROLL_PARALLAX_FACTOR +
                mInputLayout.getHeight() / 10f; // additional space
        float value = max;
        float abs = Math.abs(mRecyclerView.computeVerticalScrollOffset() / INPUT_SCROLL_PARALLAX_FACTOR);
        if (abs < max) {
            value = abs;
        }
        mInputLayout.setTranslationY(-value);
    }

    /**
     * Enable or disable main controls.
     *
     * @param enabled new state
     */
    private void setControlsState(boolean enabled) {
        mInput.setEnabled(enabled);
        mLookup.setEnabled(enabled);
        mRecyclerView.setEnabled(enabled);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Dialogs
    ///////////////////////////////////////////////////////////////////////////

    private void showHistoryDialog() {
        if (mHistory.isEmpty()) {
            Toast.makeText(this, R.string.msg_history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dialog_history);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, mHistory);
        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                startLookup(mHistory.get(which));
            }
        });
        builder.show();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Lookup
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Start lookup process, sending actual query.
     *
     * @param text text to lookup
     */
    private void startLookup(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        showProgressBar();
        mInputManager.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
        mInput.clearFocus();
        _presenter.lookup(text);
    }

    private void startLookup() {
        startLookup(mInput.getText().toString());
    }

    /**
     * Called from {@link MainPresenter}, when languages were fetched from cache/net.
     *
     * @param languages   list of languages
     * @param destIndex   selected dest index
     * @param sourceIndex selected source index
     */
    public void onLanguagesResult(List<Language> languages, int destIndex, int sourceIndex) {
        LanguageAdapter adapter = new LanguageAdapter(this, languages);
        mSpinnerSource.setAdapter(adapter);
        mSpinnerSource.setSelection(sourceIndex);
        mSpinnerSource.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (_presenter.setSourceLanguage(position)) {
                    startLookup();
                }
                _presenter.sortLanguages();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                _presenter.sortLanguages();
            }
        });
        mSpinnerDest.setAdapter(adapter);
        mSpinnerDest.setSelection(destIndex);
        mSpinnerDest.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (_presenter.setDestLanguage(position)) {
                    startLookup();
                }
                _presenter.sortLanguages();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                _presenter.sortLanguages();
            }
        });

        mToolbarInnerLayout.setVisibility(View.VISIBLE);
        mToolbarInnerLayout.setTranslationY(-mToolbarInnerLayout.getHeight());
        mToolbarInnerLayout.setAlpha(0);
        mToolbarInnerLayout.animate()
                .alpha(1)
                .translationY(0)
                .setDuration(TOOLBAR_ANIM_IN_DURATION)
                .setInterpolator(new DecelerateInterpolator(2.5f))
                .start();

        setControlsState(true);

        // if we are not coming from share intent
        if (!handleIntent(getIntent())) {
            if (mLastResult == null) {
                mInput.requestFocus();
            } else {
                onLookupResult(mLastResult);
            }
        }
    }

    private void swapLanguages() {
        if (!_presenter.swapLanguages()) {
            return;
        }
        startLookup();

        // anim stuff

        float rotation = 180f;
        if (mSwapLangs.getRotation() > 0) {
            rotation *= -1;
        }
        mSwapLangs.setRotation(0);
        mSwapLangs.animate()
                .rotationBy(rotation)
                .setDuration(SWAP_LANGS_ANIM_DURATION)
                .start();

        mSpinnerDest.setEnabled(false);
        mSpinnerSource.setEnabled(false);
        SwitchAnimation anim = new SwitchAnimation(mSpinnerDest,
                -mSpinnerDest.getHeight(),
                0f,
                SWITCH_ANIM_DURATION,
                new SwitchAnimation.OnSwitchListener() {
                    @Override
                    public void onSwitch() {
                        OnItemSelectedListener listener = mSpinnerDest.getOnItemSelectedListener();
                        mSpinnerDest.setOnItemSelectedListener(null);
                        mSpinnerDest.setSelection(_presenter.getDestLanguageIndex());
                        mSpinnerDest.setOnItemSelectedListener(listener);
                        listener = mSpinnerSource.getOnItemSelectedListener();
                        mSpinnerSource.setOnItemSelectedListener(null);
                        mSpinnerSource.setSelection(_presenter.getSourceLanguageIndex());
                        mSpinnerSource.setOnItemSelectedListener(listener);
                        mSpinnerDest.setEnabled(true);
                        mSpinnerSource.setEnabled(true);
                    }
                });
        anim.start();
        anim = new SwitchAnimation(mSpinnerSource, mSpinnerSource.getHeight(), 0, SWITCH_ANIM_DURATION, null);
        anim.start();
    }

    /**
     * Called from {@link MainPresenter} when received lookup result from server
     *
     * @param result result object returned from server
     */
    public void onLookupResult(Result result) {
        mLastResult = result;
        if (!mHistory.contains(result.text)) {
            mHistory.add(result.text);
        }
        List<TranslationEx> translations = result.translations;
        mInput.setText(result.text);
        if (!TextUtils.isEmpty(result.transcription)) {
            mTranscription.setText(String.format("[%s]", result.transcription));
        } else {
            mTranscription.setText("");
        }
        mRecyclerViewAdapter.setData(translations);
        mRecyclerView.scrollToPosition(0);
        hideProgressBar();
        updateInputLayoutPosition();
        if (_prefs.showShareFab() && mShareFab.getVisibility() != View.VISIBLE) {
            showShareFab();
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Errors
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Called when error occured. Typically it's related to server or connection problems/wrong
     * request.
     *
     * @param messaage text to show
     */
    public void onError(String messaage) {
        if (messaage == null) {
            messaage = getString(R.string.error);
        }
        Snackbar snackbar = Snackbar.make(mInput, messaage, Snackbar.LENGTH_LONG);
        snackbar.setAction(android.R.string.ok, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // empty callback for showing OK button
            }
        });
        snackbar.show();
        hideProgressBar();
    }

    /**
     * Show error when no results were received.
     */
    public void onEmptyResult() {
        onError(getString(R.string.error_no_results));
    }

    /**
     * If error was occured while fetching languages.
     */
    public void onLanguagesError() {
        setControlsState(false);
        Snackbar snackbar = Snackbar.make(mInput, R.string.error_langs, Snackbar.LENGTH_INDEFINITE);
        snackbar.setAction(R.string.retry, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                _presenter.loadLanguages();
            }
        });
        snackbar.show();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Utility
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Share last result (currently displaying to user).
     */
    private void shareLastResult() {
        if (mLastResult == null) {
            Toast.makeText(MainActivity.this, R.string.error_share, Toast.LENGTH_SHORT).show();
        } else {
            String[] result = getShareResult();
            Intent intent = ShareCompat.IntentBuilder
                    .from(this)
                    .setType("text/plain")
                    .setSubject(result[0])
                    .setText(result[1])
                    .createChooserIntent();
            startActivityForResult(intent, REQUEST_CODE_SHARE);
        }
    }

    /**
     * Show the progress bar.
     */
    private void showProgressBar() {
        if (mProgressBar.getVisibility() == View.INVISIBLE) {
            mProgressBar.setAlpha(0);
            mProgressBar.setVisibility(View.VISIBLE);
        } else if (mProgressBar.getAlpha() == 1) {
            return;
        }
        mProgressBar.animate()
                .alpha(1)
                .setDuration(PROGRESS_ANIM_DURATION)
                .start();
    }

    /**
     * Hide the progress bar.
     */
    private void hideProgressBar() {
        if (mProgressBar.getVisibility() == View.VISIBLE) {
            mProgressBar.animate()
                    .alpha(0)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mProgressBar.setVisibility(View.INVISIBLE);
                        }
                    })
                    .setDuration(PROGRESS_ANIM_DURATION)
                    .start();
        }
    }

    /**
     * Show share button.
     */
    private void showShareFab() {
        mRecyclerViewAdapter.showExtraSpace(true);
        mShareFab.setVisibility(View.VISIBLE);
        mShareFab.setScaleX(0);
        mShareFab.setScaleY(0);
        mShareFab.animate()
                .scaleX(1)
                .scaleY(1)
                .setDuration(SHARE_FAB_ANIM_DURATION)
                .setInterpolator(new DecelerateInterpolator(2))
                .start();
    }

    /**
     * Convert {@link Result} into "shareable" form.
     *
     * @return array of 2 strings, first is the text, second is the translations
     */
    @Size(2)
    private String[] getShareResult() {
        String[] result = new String[2];
        result[0] = mLastResult.text;
        if (_prefs.shareIncludeTranscription()) {
            result[0] = result[0] + " [" + mLastResult.transcription + "]";
        }
        result[1] = mLastResult.toString();
        return result;
    }

}
