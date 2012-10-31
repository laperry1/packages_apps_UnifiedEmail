/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
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

package com.android.mail;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.android.mail.providers.Account;
import com.android.mail.providers.AccountObserver;
import com.android.mail.providers.Folder;
import com.android.mail.providers.FolderWatcher;
import com.android.mail.providers.RecentFolderObserver;
import com.android.mail.ui.ControllableActivity;
import com.android.mail.ui.ConversationListCallbacks;
import com.android.mail.ui.RecentFolderController;
import com.android.mail.ui.RecentFolderList;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

import java.util.ArrayList;

/**
 * An adapter to return the list of accounts and folders for the Account Spinner.
 * This class keeps the account and folder information and returns appropriate views.
 */
public class AccountSpinnerAdapter extends BaseAdapter {
    private final ConversationListCallbacks mActivityController;
    private final LayoutInflater mInflater;
    /**
     * The position of the current account being viewed.
     */
    private Account mCurrentAccount = null;
    /**
     * Total number of accounts.
     */
    private int mNumAccounts = 0;
    /**
     * Array of all the accounts on the device.
     */
    private Account[] mAllAccounts = new Account[0];
    /**
     * An object that provides a collection of recent folders, per account.
     */
    private RecentFolderList mRecentFolders;
    /**
     * The actual collection of sorted recent folders obtained from {@link #mRecentFolders}
     */
    private ArrayList<Folder> mRecentFolderList = new ArrayList<Folder>();
    /**
     * Boolean indicating whether the "Show All Folders" items should be shown
     */
    private boolean mShowAllFoldersItem;
    /**
     * Set to true to enable recent folders, false to disable.
     */
    private boolean mRecentFoldersVisible;

    /** The folder currently being viewed */
    private Folder mCurrentFolder;
    private final Context mContext;
    /** Maintains the most fresh default inbox folder for each account.  Used for unread counts. */
    private final FolderWatcher mFolderWatcher;

    /** Type indicating the current account view shown in the actionbar (not the dropdown) */
    private static final int TYPE_NON_DROPDOWN = -99;
    /** Type indicating an account (user@example.com). */
    public static final int TYPE_ACCOUNT = 0;
    /** Type indicating a view that separates the account list from the recent folder list. */
    public static final int TYPE_HEADER = AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER;
    /** Type indicating a view containing a recent folder (Sent, Outbox). */
    public static final int TYPE_FOLDER = 1;
    /** Type indicating the "Show All Folders" view. */
    public static final int TYPE_ALL_FOLDERS = 2;
    private RecentFolderObserver mRecentFolderObserver;
    private RecentFolderObserver mSpinnerRecentFolderObserver = new RecentFolderObserver() {
        @Override
        public void onChanged() {
            requestRecentFolders();
        }
    };
    private static final String LOG_TAG = LogTag.getLogTag();

    final AccountObserver mAccountObserver = new AccountObserver() {
        @Override
        public void onChanged(Account newAccount){
            // If the account is missing or we have no accounts array, we cannot
            // proceed.
            if (newAccount == null) {
                return;
            }
            if (newAccount.uri.equals(getCurrentAccountUri())) {
                // The current account matches what is being given, get out.
                return;
            }
            mCurrentAccount = newAccount;
            if (mRecentFoldersVisible) {
                final int pos = Account.findPosition(mAllAccounts, newAccount.uri);
                LogUtils.d(LOG_TAG, "setCurrentAccount: mCurrentAccountPos = %d", pos);
                if (pos >= 0) {
                    requestRecentFolders();
                }
            }
            notifyDataSetChanged();
        }
    };
    private RecentFolderController mRecentFolderController;

    @Override
    public int getViewTypeCount() {
        return 4;
    }

    /**
     * There can be three types of views: Accounts (test@android.com, fifi@example.com), folders
     * (Inbox, Outbox) or header and footer. This method returns the type of account at given
     * position in the drop down list.
     * @param position
     * @return the type of account: one of {@link #TYPE_ACCOUNT}, {@link #TYPE_HEADER}, or
     * {@link #TYPE_FOLDER}.
     */
    @Override
    public int getItemViewType(int position) {
        // First the accounts
        if (position < mNumAccounts) {
            return TYPE_ACCOUNT;
        }
        // Then the header
        if (position == mNumAccounts) {
            return TYPE_HEADER;
        }
        if (mShowAllFoldersItem && getRecentOffset(position) >= mRecentFolderList.size()) {
            return TYPE_ALL_FOLDERS;
        }
        // Finally, the recent folders.
        return TYPE_FOLDER;
    }

    /**
     * Given a position in the list, what offset does it correspond to in the Recent Folders
     * list?
     * @param position
     * @return
     */
    private final int getRecentOffset(int position) {
        return position - mNumAccounts - 1;
    }

    /**
     * Create a spinner adapter with the context and the list of recent folders.
     * @param activity
     * @param context
     * @param showAllFolders
     */
    public AccountSpinnerAdapter(ControllableActivity activity, Context context,
            boolean showAllFolders) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mShowAllFoldersItem = showAllFolders;
        // Owned by the AccountSpinnerAdapter since nobody else needed it. Move
        // to controller if required. The folder watcher tells us directly when
        // new data is available. We are only interested in unread counts at this point.
        mFolderWatcher = new FolderWatcher(activity, this);
        mCurrentAccount = mAccountObserver.initialize(activity.getAccountController());
        mActivityController = activity.getListHandler();
        mRecentFolderController = activity.getRecentFolderController();
    }

    /**
     * Set the accounts for this spinner.
     * @param accounts
     */
    public void setAccountArray(Account[] accounts) {
        final Uri currentAccount = getCurrentAccountUri();
        mAllAccounts = accounts;
        mNumAccounts = accounts.length;
        if (!isCurrentAccountInvalid()) {
            final int pos = Account.findPosition(accounts, currentAccount);
            LogUtils.d(LOG_TAG, "setAccountArray: mCurrentAccountPos = %d", pos);
        }
        // Go through all the accounts and add the default inbox to our watcher.
        for (int i=0; i < mNumAccounts; i++) {
            final Uri uri = mAllAccounts[i].settings.defaultInbox;
            mFolderWatcher.startWatching(uri);
        }
        notifyDataSetChanged();
    }

    /**
     * Set the current folder.
     * @param folder
     * @return if changed.
     */
    public boolean setCurrentFolder(Folder folder) {
        if (folder != null && folder != mCurrentFolder) {
            mCurrentFolder = folder;
            // This calls notifyDataSetChanged() so another call is unnecessary.
            requestRecentFolders();
            return true;
        }
        return false;
    }

    @Override
    public int getCount() {
        // If the recent folders are visible, then one header, recent folders, plus one if the
        // "show all folders" item should be shown
        final int numRecents = mRecentFolderList == null? 0 : mRecentFolderList.size();
        final int numFolders = (mRecentFoldersVisible && numRecents > 0) ?
                (1 + numRecents + (mShowAllFoldersItem ? 1 : 0)) : 0;
        return mNumAccounts + numFolders;
    }

    @Override
    public Object getItem(int position) {
        switch (getItemViewType(position)){
            case TYPE_ACCOUNT:
                return getAccount(position);
            case TYPE_HEADER:
                return "account spinner header";
            case TYPE_ALL_FOLDERS:
                return "show all folders";
            default:
                // Return the folder at this location.
                return mRecentFolderList.get(getRecentOffset(position));
        }
    }

    @Override
    public long getItemId(int position) {
        final int type = getItemViewType(position);
        switch (type) {
            case TYPE_HEADER:
                // Fall-through
            case TYPE_ALL_FOLDERS:
                return type;
            case TYPE_ACCOUNT:
                return getAccount(position).uri.hashCode();
            default:
                return mRecentFolderList.get(getRecentOffset(position)).uri.hashCode();
        }
    }

    /**
     * Returns whether the current account is an invalid offset into the array.
     * @return true if the current account is invalid, and false otherwise.
     */
    private boolean isCurrentAccountInvalid() {
        return mCurrentAccount == null;
    }

    private String getCurrentAccountName() {
        if (isCurrentAccountInvalid()) {
            return "";
        }
        return mCurrentAccount.name;
    }

    private Uri getCurrentAccountUri() {
        if (isCurrentAccountInvalid()) {
            return Uri.EMPTY;
        }
        return mCurrentAccount.uri;
    }

    @Override
    public boolean hasStableIds() {
        // The ID is the hash of the URI of the object.
        return true;
    }

    @Override
    public boolean isEmpty() {
        // No item will be empty.
        return false;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        if (position == 0) {
            // Commit any leave behind items.
            mActivityController.commitDestructiveActions(false);
        }
        // Shown in the first text view with big font.
        String bigText = "";
        // Shown in the second text view with smaller font.
        String smallText = "";
        int unreadCount = 0;
        // Do not use stop view recycling in getDropDownView!!!
        // For unknown reasons, using view recycling avoids bugs where the unread count used to
        // disappear.
        final int type = getItemViewType(position);
        switch (type) {
            case TYPE_ACCOUNT:
                final Account account = getAccount(position);
                view = mInflater.inflate(R.layout.account_switch_spinner_dropdown_account, parent,
                        false);
                View colorView = view.findViewById(R.id.dropdown_color);
                if (account == null) {
                    bigText = "";
                    smallText = "";
                    unreadCount = 0;
                    colorView.setVisibility(View.INVISIBLE);
                } else {
                    bigText = account.settings.defaultInboxName;
                    smallText = account.name;
                    final int color = account.color;
                    if (color != 0) {
                        colorView.setVisibility(View.VISIBLE);
                        colorView.setBackgroundColor(color);
                    } else {
                        colorView.setVisibility(View.INVISIBLE);
                    }
                    final Folder inbox = mFolderWatcher.get(account.settings.defaultInbox);
                    unreadCount = (inbox != null) ? inbox.unreadCount : 0;
                }
                displayOrHide(view, R.id.dropdown_first, bigText);
                displayOrHide(view, R.id.dropdown_second, smallText);
                populateUnreadCountView(
                        (TextView) view.findViewById(R.id.dropdown_unread), unreadCount);
                break;
            case TYPE_HEADER:
                view = mInflater.inflate(R.layout.account_switch_spinner_dropdown_header, parent,
                        false);
                ((TextView) view.findViewById(R.id.account_spinner_header_account))
                        .setText(getCurrentAccountName());
                return view;
            case TYPE_FOLDER:
                view = mInflater.inflate(R.layout.account_switch_spinner_dropdown_folder, parent,
                        false);
                final Folder folder = mRecentFolderList.get(getRecentOffset(position));
                colorView = view.findViewById(R.id.dropdown_color);
                bigText = folder.name;
                unreadCount = folder.unreadCount;
                Folder.setFolderBlockColor(folder, colorView);
                colorView.setVisibility(View.VISIBLE);
                displayOrHide(view, R.id.dropdown_first, bigText);
                displayOrHide(view, R.id.dropdown_second, smallText);
                populateUnreadCountView(
                        (TextView) view.findViewById(R.id.dropdown_unread), unreadCount);
                break;
            case TYPE_ALL_FOLDERS:
                view = mInflater.inflate(R.layout.account_switch_spinner_dropdown_footer, parent,
                        false);
                break;
        }
        return view;
    }

    /**
     * Sets the text of the TextView to the given text, if it is non-empty. If the given
     * text is empty, then the TextView is hidden (set to View.GONE).
     * @param v
     * @param resourceId
     * @param toDisplay the given text
     */
    static private void displayOrHide(View v, int resourceId, String toDisplay) {
        final TextView target = (TextView) v.findViewById(resourceId);
        if (target == null) {
            return;
        }
        if (TextUtils.isEmpty(toDisplay)) {
            target.setVisibility(View.GONE);
            return;
        }
        target.setText(toDisplay);
    }

    /**
     * Sets the unread count, which is a nonzero number or an empty string if there are no unread
     * messages.
     * @param unreadCountView
     * @param unreadCount
     */
    private final void populateUnreadCountView(TextView unreadCountView, int unreadCount) {
        unreadCountView.setText(Utils.getUnreadCountString(mContext, unreadCount));
    }

    /**
     * Returns the account given position in the spinner.
     * @param position
     * @return the account at the given position.
     */
    private Account getAccount(int position) {
        if (position >= mNumAccounts) {
            return null;
        }
        return mAllAccounts[position];
    }


    @Override
    public boolean isEnabled(int position) {
        // Don't want the user selecting the header.
        final int type = getItemViewType(position);
        return type != TYPE_HEADER;
    }

    @Override
    public boolean areAllItemsEnabled() {
        // The header is not enabled, so return false here.
        return false;
    }

    /**
     * Notify that the folder has changed.
     */
    public void onFolderUpdated(Folder folder) {
        mCurrentFolder = folder;
        notifyDataSetChanged();
    }

    /**
     * Cause a refresh of the recent folders for the current folder and redraw the spinner with
     * the new information.
     */
    private void requestRecentFolders() {
        final Uri uri = mCurrentFolder == null ? null : mCurrentFolder.uri;
        if (mRecentFoldersVisible) {
            mRecentFolderList = mRecentFolders.getRecentFolderList(uri);
            notifyDataSetChanged();
        } else {
            mRecentFolderList = null;
        }
    }

    /**
     * Disable recent folders. Can be enabled again with {@link #enableRecentFolders()}
     */
    public void disableRecentFolders() {
        if (mRecentFoldersVisible) {
            if (mRecentFolderObserver != null) {
                mRecentFolderObserver.unregisterAndDestroy();
                mRecentFolderObserver = null;
            }
            mRecentFolders = null;
            notifyDataSetChanged();
            mRecentFoldersVisible = false;
        }
    }

    /**
     * Enable recent folders. Can be disabled again with {@link #disableRecentFolders()}
     */
    public void enableRecentFolders() {
        if (!mRecentFoldersVisible) {
            mRecentFolderObserver = mSpinnerRecentFolderObserver;
            mRecentFolders = mRecentFolderObserver.initialize(mRecentFolderController);
            mRecentFoldersVisible = true;
            // If we don't have any recent folders, request them now. Otherwise, we'll go with
            // whatever we have, and hope that the freshest ones are received through
            // requestRecentFolders().
            if (mRecentFolderList == null || mRecentFolderList.size() <= 0) {
                // This calls notifyDataSetChanged if a fresh list is available.
                requestRecentFolders();
            } else {
                notifyDataSetChanged();
            }
        }
    }

    /**
     * Destroys the spinner adapter
     */
    public void destroy() {
        mAccountObserver.unregisterAndDestroy();
        if (mRecentFolderObserver != null) {
            mRecentFolderObserver.unregisterAndDestroy();
            mRecentFolderObserver = null;
        }
    }

    /**
     * Returns true if we have any recent folders, false otherwise.
     * @return
     */
    public final boolean hasRecentFolders() {
        return mRecentFolderList != null && mRecentFolderList.size() > 0;
    }
}
