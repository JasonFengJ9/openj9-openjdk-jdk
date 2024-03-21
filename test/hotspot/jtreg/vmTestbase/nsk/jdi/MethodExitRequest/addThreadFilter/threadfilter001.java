/*
 * Copyright (c) 2001, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package nsk.jdi.MethodExitRequest.addThreadFilter;

import nsk.share.*;
import nsk.share.jpda.*;
import nsk.share.jdi.*;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import java.util.*;
import java.io.*;

/**
 * The test for the implementation of an object of the type
 * MethodExitRequest.
 *
 * The test checks that results of the method
 * <code>com.sun.jdi.MethodExitRequest.addThreadFilter()</code>
 * complies with its spec.
 *
 * The test checks up on the following assertion:
 *    Restricts the events generated by this request
 *    to those in the given thread.
 *
 * The test works as follows.
 * - The debugger resumes the debuggee and waits for the BreakpointEvent.
 * - The debuggee creates two threads, thread1 and thread2, and invokes
 *   the methodForCommunication to be suspended and
 *   to inform the debugger with the event.
 * - Upon getting the BreakpointEvent, the debugger
 *   - sets up a MethodExitRequest within the method
 *     in the class threadfilter001aTestClass which will be calling by both threads,
 *   - restricts the method entry event only for thread1,
 *   - resumes debuggee's main thread, and
 *   - waits for the event.
 * - Debuggee's main thread starts both threads.
 * - Upon getting the event, the debugger performs the checks required.
 */

public class threadfilter001 extends TestDebuggerType1 {

    public static void main (String argv[]) {
        int result = run(argv,System.out);
        if (result != 0) {
            throw new RuntimeException("TEST FAILED with result " + result);
        }
    }

    public static int run (String argv[], PrintStream out) {
        debuggeeName = "nsk.jdi.MethodExitRequest.addThreadFilter.threadfilter001a";
        return new threadfilter001().runThis(argv, out);
    }

    private String testedClassName =
      "nsk.jdi.MethodExitRequest.addThreadFilter.threadfilter001aTestClass";

    protected void testRun() {
        EventRequest eventRequest1 = null;
        ThreadReference thread1 = null;
        String thread1Name = "thread1";
        String property1 = "MethodExitRequest1";
        ReferenceType testClassReference = null;

        for (int i = 0; ; i++) {

            if (!shouldRunAfterBreakpoint()) {
                vm.resume();
                break;
            }

            display(":::::: case: # " + i);

            switch (i) {

                case 0:
                testClassReference =
                     (ReferenceType) vm.classesByName(testedClassName).get(0);
                thread1 = (ThreadReference) debuggeeClass.getValue(
                                            debuggeeClass.fieldByName(thread1Name));

                eventRequest1 = setting2MethodExitRequest (thread1,
                                       testClassReference,
                                       EventRequest.SUSPEND_ALL, property1);

                display("......waiting for MethodExitEvent in tested thread");

                Event newEvent = eventHandler.waitForRequestedEvent(new EventRequest[]{eventRequest1}, waitTime, true);

                if ( !(newEvent instanceof MethodExitEvent)) {
                     setFailedStatus("ERROR: new event is not MethodExitEvent");
                } else {

                    String property = (String) newEvent.request().getProperty("number");
                    display("       got new MethodExitEvent with property 'number' == " + property);

                    if ( !property.equals(property1) ) {
                        setFailedStatus("ERROR: property is not : " + property1);
                    }
                }
                vm.resume();
                break;

                default:
                throw new Failure("** default case 2 **");
            }

        }
        return;
    }

    private MethodExitRequest setting2MethodExitRequest ( ThreadReference thread,
                                                            ReferenceType   testedClass,
                                                            int             suspendPolicy,
                                                            String          property       ) {
        try {
            display("......setting up MethodExitRequest:");
            display("       thread: " + thread + "; class: " + testedClass +  "; property: " + property);

            MethodExitRequest
            menr = eventRManager.createMethodExitRequest();
            menr.putProperty("number", property);
            if (thread != null)
                menr.addThreadFilter(thread);
            menr.addClassFilter(testedClass);
            menr.setSuspendPolicy(suspendPolicy);

            display("      a MethodExitRequest has been set up");
            return menr;
        } catch ( Exception e ) {
            throw new Failure("** FAILURE to set up MethodExitRequest **");
        }
    }
}
