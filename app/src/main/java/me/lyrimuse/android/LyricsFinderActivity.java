package me.lyrimuse.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual lyric finder + live detection panel: search any title across both sources,
 * see which entries carry word-by-word timings, and apply one to the current song.
 */
public class LyricsFinderActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<LyricsRepository.Candidate> results = new ArrayList<>();

    private EditText query;
    private ProgressBar progress;
    private TextView status, detected, match, empty;
    private CandidateAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_finder);

        query = findViewById(R.id.query);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        detected = findViewById(R.id.detected);
        match = findViewById(R.id.match);
        empty = findViewById(R.id.empty);

        ListView list = findViewById(R.id.list);
        adapter = new CandidateAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            LyricsRepository.Candidate c = results.get(position);
            if (c.lyrics == null) {
                Toast.makeText(this, "That entry has no timed lyrics", Toast.LENGTH_SHORT).show();
                return;
            }
            PlayerService.applyManualLyrics(c);
            Toast.makeText(this, "Using " + c.source, Toast.LENGTH_SHORT).show();
            finish();
        });

        Switch auto = findViewById(R.id.swAutoRetry);
        auto.setChecked(Prefs.autoRetry(this));
        auto.setOnCheckedChangeListener((b, on) -> Prefs.setAutoRetry(this, on));

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnSearch).setOnClickListener(v -> search());
        findViewById(R.id.btnAuto).setOnClickListener(v -> {
            PlayerService.refreshLyrics();
            Toast.makeText(this, "Re-running automatic match…", Toast.LENGTH_SHORT).show();
            ui.postDelayed(this::refreshDetection, 1200);
        });

        String q = PlayerService.title;
        if (!PlayerService.artist.isEmpty()) q = q + " " + PlayerService.artist;
        query.setText(q);
        refreshDetection();
        query.setSelection(query.getText().length());
        if (!q.isEmpty()) ui.postDelayed(this::search, 400);
    }

    private void refreshDetection() {
        String app = PlayerService.playerApp.isEmpty() ? "no player" : PlayerService.playerApp;
        detected.setText(PlayerService.title.isEmpty()
                ? "Live detection · waiting for a song"
                : "Live detection · " + app + "\n" + PlayerService.title
                + (PlayerService.artist.isEmpty() ? "" : " — " + PlayerService.artist));
        Lyrics l = PlayerService.lyrics;
        if (l == null) {
            match.setText("No lyrics attached yet · " + PlayerService.status);
        } else {
            match.setText("Attached: " + PlayerService.source + " · " + l.lines.size() + " lines"
                    + (l.wordLevel ? " · word-by-word" : " · line timing"));
        }
    }

    private void search() {
        final String q = query.getText().toString().trim();
        if (q.isEmpty()) return;
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        status.setText("Searching NetEase + LRCLIB…");
        results.clear();
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            final List<LyricsRepository.Candidate> found = LyricsRepository.searchCandidates(q, PlayerService.duration());
            ui.post(() -> {
                results.clear();
                results.addAll(found);
                adapter.notifyDataSetChanged();
                progress.setVisibility(View.GONE);
                if (found.isEmpty()) {
                    status.setText("No matches");
                    empty.setVisibility(View.VISIBLE);
                } else {
                    status.setText(found.size() + " matches · tap one to use it");
                }
                refreshDetection();
            });
        }, "lyrimuse-search").start();
    }

    private class CandidateAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return results.size();
        }

        @Override
        public Object getItem(int position) {
            return results.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(LyricsFinderActivity.this)
                        .inflate(R.layout.item_candidate, parent, false);
            }
            LyricsRepository.Candidate c = results.get(position);
            TextView title = v.findViewById(R.id.itemTitle);
            TextView subtitle = v.findViewById(R.id.itemSubtitle);
            TextView badge = v.findViewById(R.id.itemBadge);

            title.setText(c.title.isEmpty() ? "Unknown title" : c.title);
            subtitle.setText(c.subtitle());
            if (c.lyrics == null) {
                badge.setText("no lyrics");
                badge.setTextColor(0xFF8A90A0);
            } else if (c.wordLevel) {
                badge.setText("WORD · " + c.lines);
                badge.setTextColor(0xFFE8C9A0);
            } else {
                badge.setText("LINE · " + c.lines);
                badge.setTextColor(0xFFB9C0CE);
            }
            return v;
        }
    }
}
