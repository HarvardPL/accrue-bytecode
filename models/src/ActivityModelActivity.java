/*
 *
 * Copyright (c) 2009-2012,
 *
 *  Steve Suh           <suhsteve@gmail.com>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. The names of the contributors may not be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 *
 */

package activity.model;

import android.app.Activity;
import android.os.Bundle;

public class ActivityModelActivity extends Activity {
        /*   |, /, \ flow down
     *   ^ flow up , <= flow left, => flow right
     *
     *                     onCreate(...)
     *                          |
     *  ================>    onStart()
     *  ^                  /          \
     *  ^            onStop() <====   onResume()  <======
     *  ^              /   \      ^<====      |         ^
     *  ^ <= onRestart()  onDestroy()  ^<= onPause() => ^
     *                         |
     *                       (kill) 
     *                       
     */
	public void ActivityModel() {
    	//is null correct for savedInstanceState?
		//com.galois.ReadsContactApp.ReadsContactApp rca = new com.galois.ReadsContactApp.ReadsContactApp();
		onCreate(null);
    	while(true) { //while loop #1
    		onStart();
    		int br1 = (new Double(Math.floor(2*Math.random()))).intValue();
    		if (br1 == 0) {
    			onStop();
        		int br2 = (new Double(Math.floor(2*Math.random()))).intValue();
        		if (br2 == 0) {
        			onRestart();
        			continue;
        		}
        		else {
        			onDestroy();
        			break; //break out of loop #1
        		}
    		}
    		else {
    			while (true) { //while loop #2
    				onResume();
    	    		int br3 = (new Double(Math.floor(2*Math.random()))).intValue();
    	    		if (br3 == 0) {
    	    			break; //break out of loop #2
    	    		}
    	    		else {
    	    			onPause();
    	    			continue;
    	    		}
    			}
    			onStop();
        		int br4 = (new Double(Math.floor(2*Math.random()))).intValue();
        		if (br4 == 0) {
        			onRestart();
        			continue;
        		}
        		else {
        			onDestroy();
        			break; //break out of loop #1
        		}
    		}
    	}
    }
}