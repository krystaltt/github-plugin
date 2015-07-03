package org.jenkinsci.plugins.github.webhook;

import com.cloudbees.jenkins.GitHubPushTrigger;
import com.cloudbees.jenkins.GitHubRepositoryName;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import hudson.model.FreeStyleProject;
import hudson.plugins.git.GitSCM;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHHook;
import org.kohsuke.github.GHRepository;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.EnumSet;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.asList;
import static com.google.common.collect.Lists.newArrayList;
import static org.hamcrest.Matchers.is;
import static org.jenkinsci.plugins.github.webhook.WebhookManager.forHookUrl;
import static org.junit.Assert.assertThat;
import static org.kohsuke.github.GHEvent.CREATE;
import static org.kohsuke.github.GHEvent.PULL_REQUEST;
import static org.kohsuke.github.GHEvent.PUSH;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author lanwen (Merkushev Kirill)
 */
@RunWith(MockitoJUnitRunner.class)
public class WebhookManagerTest {

    public static final GitSCM GIT_SCM = new GitSCM("ssh://git@github.com/dummy/dummy.git");
    public static final URL HOOK_ENDPOINT = endpoint("http://hook.endpoint/");
    public static final URL ANOTHER_HOOK_ENDPOINT = endpoint("http://another.url/");
    
    @Rule
    public JenkinsRule jenkins = new JenkinsRule();
    
    @Spy
    private WebhookManager manager = forHookUrl(HOOK_ENDPOINT);

    @Spy
    private GitHubRepositoryName nonactive = new GitHubRepositoryName("github.com", "dummy", "dummy");

    @Spy
    private GitHubRepositoryName active = new GitHubRepositoryName("github.com", "dummy", "active");

    @Mock
    private GHRepository repo;


    @Test
    public void shouldDoNothingOnNoAdminRights() throws Exception {
        manager.unregisterFor(nonactive, newArrayList(active));
        verify(manager, times(1)).withAdminAccess();
        verify(manager, never()).fetchHooks();
    }

    @Test
    public void shouldSearchBothWebAndServiceHookOnNonActiveName() throws Exception {
        when(nonactive.resolve()).thenReturn(newArrayList(repo));
        when(repo.hasAdminAccess()).thenReturn(true);

        manager.unregisterFor(nonactive, newArrayList(active));

        verify(manager, times(1)).serviceWebhookFor(HOOK_ENDPOINT);
        verify(manager, times(1)).webhookFor(HOOK_ENDPOINT);
        verify(manager, times(1)).fetchHooks();
    }

    @Test
    public void shouldSearchOnlyServiceHookOnActiveName() throws Exception {
        when(active.resolve()).thenReturn(newArrayList(repo));
        when(repo.hasAdminAccess()).thenReturn(true);

        manager.unregisterFor(active, newArrayList(active));

        verify(manager, times(1)).serviceWebhookFor(HOOK_ENDPOINT);
        verify(manager, never()).webhookFor(HOOK_ENDPOINT);
        verify(manager, times(1)).fetchHooks();
    }

    @Test
    @WithoutJenkins
    public void shouldMatchAdminAccessWhenTrue() throws Exception {
        when(repo.hasAdminAccess()).thenReturn(true);

        assertThat("has admin access", manager.withAdminAccess().apply(repo), is(true));
    }

    @Test
    @WithoutJenkins
    public void shouldMatchAdminAccessWhenFalse() throws Exception {
        when(repo.hasAdminAccess()).thenReturn(false);

        assertThat("has no admin access", manager.withAdminAccess().apply(repo), is(false));
    }

    @Test
    @WithoutJenkins
    public void shouldMatchWebHook() {
        when(repo.hasAdminAccess()).thenReturn(false);

        GHHook hook = hook(HOOK_ENDPOINT, PUSH);

        assertThat("webhook has web name and url prop", manager.webhookFor(HOOK_ENDPOINT).apply(hook), is(true));
    }

    @Test
    @WithoutJenkins
    public void shouldNotMatchOtherUrlWebHook() {
        when(repo.hasAdminAccess()).thenReturn(false);

        GHHook hook = hook(ANOTHER_HOOK_ENDPOINT, PUSH);

        assertThat("webhook has web name and another url prop",
                manager.webhookFor(HOOK_ENDPOINT).apply(hook), is(false));
    }

    @Test
    public void shouldMergeEventsOnRegisterNewAndDeleteOldOnes() throws IOException {
        when(nonactive.resolve()).thenReturn(newArrayList(repo));
        when(repo.hasAdminAccess()).thenReturn(true);
        Predicate<GHHook> del = spy(Predicate.class);
        when(manager.deleteWebhook()).thenReturn(del);

        GHHook hook = hook(HOOK_ENDPOINT, CREATE);
        GHHook prhook = hook(HOOK_ENDPOINT, PULL_REQUEST);
        when(repo.getHooks()).thenReturn(newArrayList(hook, prhook));

        manager.createHookSubscribedTo(copyOf(newArrayList(PUSH))).apply(nonactive);
        verify(del, times(2)).apply(any(GHHook.class));
        verify(manager).createWebhook(HOOK_ENDPOINT, EnumSet.copyOf(newArrayList(CREATE, PULL_REQUEST, PUSH)));
    }

    @Test
    public void shouldNotAddPushEventByDefaultForProjectWithoutTrigger() throws IOException {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.setScm(GIT_SCM);

        manager.registerFor(project).run();
        verify(manager).createHookSubscribedTo(Collections.<GHEvent>emptyList());
    }

    @Test
    public void shouldAddPushEventByDefault() throws IOException {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addTrigger(new GitHubPushTrigger());
        project.setScm(GIT_SCM);

        manager.registerFor(project).run();
        verify(manager).createHookSubscribedTo(newArrayList(PUSH));
    }


    private GHHook hook(URL endpoint, GHEvent event, GHEvent... events) {
        GHHook hook = mock(GHHook.class);
        when(hook.getName()).thenReturn("web");
        when(hook.getConfig()).thenReturn(ImmutableMap.of("url", endpoint.toExternalForm()));
        when(hook.getEvents()).thenReturn(EnumSet.copyOf(asList(event, events)));
        return hook;
    }

    private static URL endpoint(String endpoint) {
        try {
            return new URL(endpoint);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
