/*
 * The MIT License
 *
 * Copyright 2015 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.branch;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import hudson.util.DescribableList;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMNavigatorDescriptor;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceOwner;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * A folder-like collection of {@link MultiBranchProject}s, one per repository.
 */
@SuppressWarnings({"unchecked", "rawtypes"}) // mistakes in various places
public final class OrganizationFolder extends ComputedFolder<MultiBranchProject<?,?>> implements SCMSourceOwner, MultiBranchProjectFactory.SCMSourceOwnerHack {

    private final DescribableList<SCMNavigator,SCMNavigatorDescriptor> navigators = new DescribableList<SCMNavigator, SCMNavigatorDescriptor>(this);
    private final DescribableList<MultiBranchProjectFactory,MultiBranchProjectFactoryDescriptor> projectFactories = new DescribableList<MultiBranchProjectFactory,MultiBranchProjectFactoryDescriptor>(this);

    public OrganizationFolder(ItemGroup parent, String name) {
        super(parent, name);
    }

    @Override public void onCreatedFromScratch() {
        super.onCreatedFromScratch();
        for (MultiBranchProjectFactoryDescriptor d : ExtensionList.lookup(MultiBranchProjectFactoryDescriptor.class)) {
            MultiBranchProjectFactory f = d.newInstance();
            if (f != null) {
                projectFactories.add(f);
            }
        }
    }

    @Override public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
        super.onLoad(parent, name);
        navigators.setOwner(this);
        projectFactories.setOwner(this);
        criteriaBySource = new WeakHashMap<SCMSource,ThreadLocal<SCMSourceCriteria>>();
    }

    public DescribableList<SCMNavigator,SCMNavigatorDescriptor> getNavigators() {
        return navigators;
    }

    public DescribableList<MultiBranchProjectFactory,MultiBranchProjectFactoryDescriptor> getProjectFactories() {
        return projectFactories;
    }

    @Override protected void submit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, Descriptor.FormException {
        navigators.rebuildHetero(req, req.getSubmittedForm(), ExtensionList.lookup(SCMNavigatorDescriptor.class), "navigators");
        projectFactories.rebuildHetero(req, req.getSubmittedForm(), ExtensionList.lookup(MultiBranchProjectFactoryDescriptor.class), "projectFactories");
    }

    @Override protected Map<String,MultiBranchProject<?,?>> computeChildren(TaskListener listener) throws IOException, InterruptedException {
        Map<String,MultiBranchProject<?,?>> projects = new HashMap<String,MultiBranchProject<?,?>>();
        for (SCMNavigator navigator : navigators) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            listener.getLogger().format("Consulting %s%n", navigator.getDescriptor().getDisplayName());
            for (Map.Entry<String,? extends List<? extends SCMSource>> entry : navigator.discoverSources(this, listener).entrySet()) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                String projectName = entry.getKey();
                if (projects.containsKey(projectName)) {
                    listener.error("Will not create duplicate subproject named " + projectName);
                    continue;
                }
                List<? extends SCMSource> sources = entry.getValue();
                for (SCMSource source : sources) {
                    source.setOwner(this);
                }
                for (MultiBranchProjectFactory factory : projectFactories) {
                    MultiBranchProject<?,?> project;
                    try {
                        project = factory.createProject(this, projectName, sources, listener);
                    } catch (InterruptedException x) {
                        throw x;
                    } catch (Exception x) {
                        x.printStackTrace(listener.error("Failed to create a subproject " + projectName));
                        continue;
                    }
                    if (project != null) {
                        List<BranchSource> branchSources = project.getSourcesList();
                        for (SCMSource source : sources) {
                            // TODO we probably to define a BranchPropertyFactory
                            branchSources.add(new BranchSource(source, new DefaultBranchPropertyStrategy(new BranchProperty[0])));
                        }
                        projects.put(projectName, project);
                        project.scheduleBuild(null);
                        break;
                    }
                }
            }
        }
        return projects;
    }

    @Override protected void updateExistingItem(MultiBranchProject<?,?> existing, MultiBranchProject<?,?> replacement) {
        existing.getSourcesList().clear();
        existing.getSourcesList().addAll(replacement.getSourcesList());
    }

    @Override public TopLevelItemDescriptor getDescriptor() {
        return Jenkins.getActiveInstance().getDescriptorByType(DescriptorImpl.class);
    }

    @Override public String getUrlChildPrefix() {
        return "job"; // make it look like a folder
    }

    /**
     * For URL binding.
     * @see #getUrlChildPrefix
     */
    public MultiBranchProject<?,?> getJob(String name) {
        return getItem(name);
    }

    @Override protected File getJobsDir() {
        return new File(getRootDir(), "jobs");
    }
    
    @Override public List<SCMSource> getSCMSources() {
        return Collections.emptyList(); // irrelevant unless onSCMSourceUpdated implemented
    }

    @Override public SCMSource getSCMSource(String sourceId) {
        return null;
    }

    @Override public void onSCMSourceUpdated(SCMSource source) {
        // possibly we should recheck whether this project remains valid
    }

    private transient Map<SCMSource,ThreadLocal<SCMSourceCriteria>> criteriaBySource = new WeakHashMap<SCMSource,ThreadLocal<SCMSourceCriteria>>();

    @Override public SCMSourceCriteria getSCMSourceCriteria(SCMSource source) {
        ThreadLocal<SCMSourceCriteria> criteriaTL = criteriaBySource.get(source);
        return criteriaTL != null ? criteriaTL.get() : null;
    }

    @Override public <T> T withSCMSourceCriteria(SCMSource source, SCMSourceCriteria criteria, Callable<T> body) throws Exception {
        ThreadLocal<SCMSourceCriteria> criteriaTL;
        synchronized (criteriaBySource) {
            criteriaTL = criteriaBySource.get(source);
            if (criteriaTL == null) {
                criteriaTL = new ThreadLocal<SCMSourceCriteria>();
                criteriaBySource.put(source, criteriaTL);
            }
        }
        SCMSourceCriteria old = criteriaTL.get();
        try {
            criteriaTL.set(criteria);
            return body.call();
        } finally {
            criteriaTL.set(old);
        }
    }
    
    @Override public String getPronoun() {
        return super.getPronoun(); // TODO
    }

    @Extension public static class DescriptorImpl extends TopLevelItemDescriptor {

        @Override public String getDisplayName() {
            return "Organization Folder";
        }

        @Override public TopLevelItem newInstance(ItemGroup parent, String name) {
            return new OrganizationFolder(parent, name);
        }

    }

}
