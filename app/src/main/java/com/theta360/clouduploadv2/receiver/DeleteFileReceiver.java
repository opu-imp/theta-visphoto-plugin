/**
 * Copyright 2018 Ricoh Company, Ltd.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.theta360.clouduploadv2.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receive end of broadcast application
 */
public class DeleteFileReceiver extends BroadcastReceiver {
    public static final String DELETE_FILE = "com.theta360.clouduploadv2.delete-file";
    public static final String TARGETS = "targets";

    private Callback mCallback;

    public DeleteFileReceiver(Callback callback) {
        mCallback = callback;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        String[] targets = intent.getStringArrayExtra(TARGETS);
        if (DELETE_FILE.equals(action)) {
            mCallback.callDeleteFileCallback(targets);
        }
    }

    public interface Callback {
        void callDeleteFileCallback(String[] targets);
    }
}
