package com.termux.app.terminow.linux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Names for the Android groups of the app that an /etc/group, written by proot-distro for the
 * app as it was at the install, does not have: like a rootfs of app ID 519 that runs as 1307.
 */
public class TerminowAndroidGroupsTest {

    private static final String GROUP = "root:x:0:\nsudo:x:27:\n" +
        "aid_u0_a519:x:10519:root,aid_u0_a519\n" +
        "aid_external_storage:x:1077:root,aid_u0_a519\n" +
        "aid_inet:x:3003:root,aid_u0_a519\n" +
        "aid_everybody:x:9997:root,aid_u0_a519\n" +
        "aid_u0_a519_cache:x:20519:root,aid_u0_a519\n" +
        "aid_all_a519:x:50519:root,aid_u0_a519\n";

    private static final String STATUS = "Name:\tcat\nUid:\t11307\t11307\t11307\t11307\nGid:\t11307\t11307\t11307\t11307\n" +
        "FDSize:\t64\nGroups:\t1077 3003 9997 21307 51307 \nNStgid:\t1\n";

    private static final String ID = "uid=11307(u0_a1307) gid=11307(u0_a1307) groups=11307(u0_a1307),1077(external_storage)," +
        "3003(inet),9997(everybody),21307(u0_a1307_cache),51307(all_a1307) context=u:r:untrusted_app:s0:c27,c261,c512,c768";

    @Test
    public void readsTheSupplementaryGroupsOfTheProcess() {
        assertEquals(Arrays.asList(1077, 3003, 9997, 21307, 51307), TerminowAndroidGroups.supplementaryGroups(STATUS));
        assertEquals(Collections.emptyList(), TerminowAndroidGroups.supplementaryGroups("Groups:\t\n"));
    }

    @Test
    public void findsOnlyTheGroupsWithoutAnEntry() {
        List<Integer> gids = TerminowAndroidGroups.supplementaryGroups(STATUS);
        assertEquals(Arrays.asList(21307, 51307), TerminowAndroidGroups.missing(GROUP, gids));
    }

    @Test
    public void namesThemAsProotDistroDoesAfterTheEntriesThatAreThere() {
        Map<Integer, String> names = TerminowAndroidGroups.androidNames(ID);
        assertEquals("u0_a1307", names.get(-1));
        assertEquals("all_a1307", names.get(51307));
        List<Integer> missing = Arrays.asList(21307, 51307);
        assertEquals("aid_u0_a1307_cache:x:21307:root,aid_u0_a1307\naid_all_a1307:x:51307:root,aid_u0_a1307\n",
            TerminowAndroidGroups.entries(GROUP, missing, names, false));
        assertEquals("aid_u0_a1307_cache:*::root,aid_u0_a1307\naid_all_a1307:*::root,aid_u0_a1307\n",
            TerminowAndroidGroups.entries(GROUP, missing, names, true));
    }

    @Test
    public void addsNothingOnceEveryGroupHasAnEntry() {
        Map<Integer, String> names = TerminowAndroidGroups.androidNames(ID);
        List<Integer> gids = TerminowAndroidGroups.supplementaryGroups(STATUS);
        String after = GROUP + TerminowAndroidGroups.entries(GROUP, TerminowAndroidGroups.missing(GROUP, gids), names, false);
        assertTrue(TerminowAndroidGroups.missing(after, gids).isEmpty());
    }

    @Test
    public void aGroupWithoutAnAndroidNameOrWithATakenNameGetsItsNumber() {
        String group = GROUP + "aid_all_a1307:x:4242:\n";
        Map<Integer, String> names = TerminowAndroidGroups.androidNames("uid=11307(u0_a1307) groups=51307(all_a1307),3009");
        assertEquals("aid_51307:x:51307:root,aid_u0_a1307\naid_3009:x:3009:root,aid_u0_a1307\n",
            TerminowAndroidGroups.entries(group, Arrays.asList(51307, 3009), names, false));
        // Without the output of id, a group is still named, by its number
        assertEquals("aid_21307:x:21307:root\n",
            TerminowAndroidGroups.entries(GROUP, Collections.singletonList(21307), TerminowAndroidGroups.androidNames(""), false));
    }

    @Test
    public void aNameThatIsNotAGroupNameIsNotWritten() {
        Map<Integer, String> names = TerminowAndroidGroups.androidNames("uid=1(a:b) groups=21307(x:y\nz)");
        assertEquals("aid_21307:x:21307:root\n",
            TerminowAndroidGroups.entries(GROUP, Collections.singletonList(21307), names, false));
    }
}
