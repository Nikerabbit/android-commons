package org.wikimedia.commons.contributions;

import android.content.*;
import android.database.Cursor;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.accounts.Account;
import android.os.Bundle;
import org.apache.commons.codec.digest.DigestUtils;
import org.wikimedia.commons.CommonsApplication;
import org.mediawiki.api.*;
import org.wikimedia.commons.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ContributionsSyncAdapter extends AbstractThreadedSyncAdapter {
    public ContributionsSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    private int getLimit() {
        return 500; // FIXME: Parameterize!
    }

    private static final String[] existsQuery = { Contribution.Table.COLUMN_FILENAME };
    private static final String existsSelection = Contribution.Table.COLUMN_FILENAME + " = ?";
    private boolean fileExists(ContentProviderClient client, String filename) {
        Cursor cursor = null;
        try {
            cursor = client.query(ContributionsContentProvider.BASE_URI,
                    existsQuery,
                    existsSelection,
                    new String[] { filename },
                    ""
            );
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
        return cursor != null && cursor.getCount() != 0;
    }

    @Override
    public void onPerformSync(Account account, Bundle bundle, String s, ContentProviderClient contentProviderClient, SyncResult syncResult) {
        // This code is fraught with possibilities of race conditions, but lalalalala I can't hear you!
        String user = account.name;
        MWApi api = CommonsApplication.createMWApi();
        SharedPreferences prefs = this.getContext().getSharedPreferences("prefs", Context.MODE_PRIVATE);
        String lastModified = prefs.getString("lastSyncTimestamp", "");
        Date curTime = new Date();
        ApiResult result;
        try {
            MWApi.RequestBuilder builder = api.action("query")
                    .param("list", "logevents")
                    .param("leaction", "upload/upload")
                    .param("leprop", "title|timestamp")
                    .param("leuser", user)
                    .param("lelimit", getLimit());
            if(!TextUtils.isEmpty(lastModified)) {
                builder.param("leend", lastModified);
            }
            result = builder.get();
        } catch (IOException e) {
            throw new RuntimeException(e); // FIXME: Maybe something else?
        }
        Log.d("Commons", "Last modified at " + lastModified);

        ArrayList<ApiResult> uploads = result.getNodes("/api/query/logevents/item");
        Log.d("Commons", uploads.size() + " results!");
        ArrayList<ContentValues> imageValues = new ArrayList<ContentValues>();
        for(ApiResult image: uploads) {
            String filename = image.getString("@title");
            if(fileExists(contentProviderClient, filename)) {
                Log.d("Commons", "Skipping " + filename);
                continue;
            }
            String thumbUrl = Utils.makeThumbBaseUrl(filename);
            Date dateUpdated = Utils.parseMWDate(image.getString("@timestamp"));
            Contribution contrib = new Contribution(null, thumbUrl, filename, "", -1, dateUpdated, dateUpdated, user, "");
            contrib.setState(Contribution.STATE_COMPLETED);
            imageValues.add(contrib.toContentValues());
        }

        try {
            contentProviderClient.bulkInsert(ContributionsContentProvider.BASE_URI, imageValues.toArray(new ContentValues[]{}));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
        prefs.edit().putString("lastSyncTimestamp", Utils.toMWDate(curTime)).apply();
        Log.d("Commons", "Oh hai, everyone! Look, a kitty!");


    }
}
