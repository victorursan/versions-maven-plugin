package org.codehaus.mojo.versions;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.artifact.filter.PatternExcludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.UpdateScope;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;
import org.codehaus.mojo.versions.utils.DependencyComparator;
import org.codehaus.plexus.util.StringUtils;

import javax.xml.stream.XMLStreamException;
import java.util.*;

/**
 * Displays all dependencies that have newer versions available.
 *
 * @author Stephen Connolly
 * @since 1.0-alpha-1
 */
@Mojo(name = "display-dependency-updates", requiresProject = true, requiresDirectInvocation = false)
public class DisplayDependencyUpdatesMojo
        extends AbstractVersionsDisplayMojo {


    private static final String END_RANGE_CHARS = "])";

    private static final String START_RANGE_CHARS = "[(";

    // ------------------------------ FIELDS ------------------------------

    /**
     * The width to pad info messages.
     *
     * @since 1.0-alpha-1
     */
    private static final int INFO_PAD_SIZE = 72;
    /**
     * Whether to process the dependencyManagement section of the project. If not set will default to true.
     *
     * @since 1.2
     */
    @Parameter(property = "processDependencyManagement", defaultValue = "true")
    protected Boolean processDependencyManagement = Boolean.TRUE;
    /**
     * Whether to process the dependencies section of the project. If not set will default to true.
     *
     * @since 1.2
     */
    @Parameter(property = "processDependencies", defaultValue = "true")
    protected Boolean processDependencies = Boolean.TRUE;
    /**
     * Whether to show additional information such as dependencies that do not need updating. Defaults to false.
     *
     * @parameter property="verbose" defaultValue="false"
     * @since 2.1
     */
    @Parameter(property = "verbose", defaultValue = "false")
    protected Boolean verbose = Boolean.FALSE;
    /**
     * A comma separated list of artifact patterns to include. Follows the pattern
     * "groupId:artifactId:type:classifier:version". Designed to allow specifing the set of includes from the command
     * line. When specifying includes from the pom, use the {@link #includes} configuration instead. If this property is
     * specified then the {@link # include} configuration is ignored.
     */
    @Parameter(property = "includes")
    private String includesList = null;
    /**
     * A comma separated list of artifact patterns to exclude. Follows the pattern
     * "groupId:artifactId:type:classifier:version". Designed to allow specifing the set of excludes from the command
     * line. When specifying excludes from the pom, use the {@link #excludes} configuration instead. If this property is
     * specified then the {@link # exclude} configuration is ignored.
     */
    @Parameter(property = "excludes")
    private String excludesList = null;
    /**
     * A list of artifact patterns to include. Follows the pattern "groupId:artifactId:type:classifier:version". This
     * configuration setting is ignored if {@link #includesList} is defined.
     */
    @Parameter
    private String[] includes = null;
    /**
     * A list of artifact patterns to exclude. Follows the pattern "groupId:artifactId:type:classifier:version". This
     * configuration setting is ignored if {@link #excludesList} is defined.
     */
    @Parameter
    private String[] excludes = null;
    /**
     * Artifact filter to determine if artifact should be included
     */
    private PatternIncludesArtifactFilter includesFilter;
    /**
     * Artifact filter to determine if artifact should be excluded
     */
    private PatternExcludesArtifactFilter excludesFilter;


    // --------------------- GETTER / SETTER METHODS ---------------------

    /**
     * Returns a set of dependencies where the dependencies which are defined in the dependency management section have
     * been filtered out.
     *
     * @param dependencies         The set of dependencies.
     * @param dependencyManagement The set of dependencies from the dependency management section.
     * @return A new set of dependencies which are from the set of dependencies but not from the set of dependency
     * management dependencies.
     * @since 1.0-beta-1
     */
    private static Set removeDependencyManagment(Set dependencies, Set dependencyManagement) {
        Set result = new TreeSet(new DependencyComparator());
        for (Iterator i = dependencies.iterator(); i.hasNext(); ) {
            Dependency c = (Dependency) i.next();
            boolean matched = false;
            Iterator j = dependencyManagement.iterator();
            while (!matched && j.hasNext()) {
                Dependency t = (Dependency) j.next();
                if (StringUtils.equals(t.getGroupId(), c.getGroupId())
                        && StringUtils.equals(t.getArtifactId(), c.getArtifactId())
                        && (t.getScope() == null || StringUtils.equals(t.getScope(), c.getScope()))
                        && (t.getClassifier() == null || StringUtils.equals(t.getClassifier(), c.getClassifier()))
                        && (c.getVersion() == null || t.getVersion() == null
                        || StringUtils.equals(t.getVersion(), c.getVersion()))) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * Indicates whether any includes were specified via the 'includes' or 'includesList' options.
     *
     * @return true if includes were specified, false otherwise.
     */
    protected boolean hasIncludes() {
        return includes != null || includesList != null;
    }

    private ArtifactFilter getIncludesArtifactFilter() {
        if (includesFilter == null && (includes != null || includesList != null)) {
            List patterns = new ArrayList();
            if (this.includesList != null) {
                patterns.addAll(separatePatterns(includesList));
            } else if (includes != null) {
                patterns.addAll(Arrays.asList(includes));
            }
            includesFilter = new PatternIncludesArtifactFilter(patterns);
        }
        return includesFilter;
    }

    private ArtifactFilter getExcludesArtifactFilter() {
        if (excludesFilter == null && (excludes != null || excludesList != null)) {
            List patterns = new ArrayList();
            if (excludesList != null) {
                patterns.addAll(separatePatterns(excludesList));
            } else if (excludes != null) {
                patterns.addAll(Arrays.asList(excludes));
            }
            excludesFilter = new PatternExcludesArtifactFilter(patterns);
        }
        return excludesFilter;
    }

    /**
     * To handle multiple includes with version range like "group:artifact:jar:[1.0.0,2.2)", we have to use a parsing a
     * little bit more complex than split().
     *
     * @param includeString the string to parse
     * @return list of patterns
     */
    protected List separatePatterns(String includeString) {
        if (includeString == null) {
            return Collections.EMPTY_LIST;
        }

        List patterns = new ArrayList();
        int indexOf = nextCommaIndex(includeString);
        while (indexOf >= 0) {
            patterns.add(includeString.substring(0, indexOf));
            includeString = includeString.substring(indexOf + 1);
            indexOf = nextCommaIndex(includeString);
        }
        patterns.add(includeString);

        return patterns;
    }

    private int nextCommaIndex(final String includeString) {

        int indexOfComma = includeString.indexOf(',');
        int nextRangeStartDelimiterIndex = findFirstChar(includeString, START_RANGE_CHARS);
        if (nextRangeStartDelimiterIndex >= 0) {
            if (!(indexOfComma >= 0 && indexOfComma < nextRangeStartDelimiterIndex)) {
                int nextStopDelimiterIndex = findFirstChar(includeString, END_RANGE_CHARS);

                // recursive call
                int tmp = nextCommaIndex(includeString.substring(nextStopDelimiterIndex + 1));
                indexOfComma = (tmp >= 0) ? nextStopDelimiterIndex + 1 + tmp : -1;
            }
        }
        return indexOfComma;

    }

    private int findFirstChar(final String includeString, final String chars) {
        int nextRangeStartDelimiterIndex = -1;

        char[] delimiters = chars.toCharArray();
        for (int i = 0; i < delimiters.length; i++) {
            int index = includeString.indexOf(delimiters[i]);
            if (index >= 0 && nextRangeStartDelimiterIndex >= 0) {
                nextRangeStartDelimiterIndex = Math.min(index, nextRangeStartDelimiterIndex);
            } else {
                if (index >= 0) {
                    nextRangeStartDelimiterIndex = index;
                }
            }
        }
        return nextRangeStartDelimiterIndex;
    }


    public boolean isProcessingDependencyManagement() {
        // true if true or null
        return !Boolean.FALSE.equals(processDependencyManagement);
    }

    public boolean isProcessingDependencies() {
        // true if true or null
        return !Boolean.FALSE.equals(processDependencies);
    }

    public boolean isVerbose() {
        // true if true or null
        return !Boolean.FALSE.equals(verbose);
    }

    // ------------------------ INTERFACE METHODS ------------------------

    // --------------------- Interface Mojo ---------------------

    /**
     * Determine if the artifact is included in the list of artifacts to be processed.
     *
     * @param artifact The artifact we want to check.
     * @return true if the artifact should be processed, false otherwise.
     */
    protected boolean isIncluded(Artifact artifact) {
        boolean result = true;

        ArtifactFilter includesFilter = this.getIncludesArtifactFilter();

        if (includesFilter != null) {
            result = includesFilter.include(artifact);
        }

        ArtifactFilter excludesFilter = this.getExcludesArtifactFilter();

        if (excludesFilter != null) {
            result = result && excludesFilter.include(artifact);
        }

        return result;
    }

    /**
     * Try to find the dependency artifact that matches the given dependency.
     *
     * @param dependency
     * @return
     */
    protected Artifact toArtifact(Dependency dependency) throws MojoExecutionException {
        Artifact artifact = findArtifact(dependency);
        if (artifact == null) {
            try {
                return getHelper().createDependencyArtifact(dependency);
            } catch (InvalidVersionSpecificationException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        return artifact;
    }

    /**
     * Compare and artifact to a dependency. Returns true only if the groupId, artifactId, type, and classifier are all
     * equal.
     *
     * @param artifact
     * @param dep
     * @return true if artifact and dep refer to the same artifact
     */
    private boolean compare(Artifact artifact, Dependency dep) {
        if (!org.apache.commons.lang.StringUtils.equals(artifact.getGroupId(), dep.getGroupId())) {
            return false;
        }
        if (!org.apache.commons.lang.StringUtils.equals(artifact.getArtifactId(), dep.getArtifactId())) {
            return false;
        }
        if (!org.apache.commons.lang.StringUtils.equals(artifact.getType(), dep.getType())) {
            return false;
        }
        if (!org.apache.commons.lang.StringUtils.equals(artifact.getClassifier(), dep.getClassifier())) {
            return false;
        }
        return true;
    }

    /**
     * Try to find the dependency artifact that matches the given dependency.
     *
     * @param dependency
     * @return
     */
    protected Artifact findArtifact(Dependency dependency) {
        if (getProject().getDependencyArtifacts() == null) {
            return null;
        }
        Iterator iter = getProject().getDependencyArtifacts().iterator();
        while (iter.hasNext()) {
            Artifact artifact = (Artifact) iter.next();
            if (compare(artifact, dependency)) {
                return artifact;
            }
        }
        return null;
    }

    /**
     * @throws org.apache.maven.plugin.MojoExecutionException when things go wrong
     * @throws org.apache.maven.plugin.MojoFailureException   when things go wrong in a very bad way
     * @see org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo#execute()
     */
    public void execute()
            throws MojoExecutionException, MojoFailureException {
        logInit();

        Set dependencyManagement = new TreeSet(new DependencyComparator());
        if (getProject().getDependencyManagement() != null) {

            List<Dependency> dependenciesFromPom = getProject().getDependencyManagement().getDependencies();
            for (Dependency dependency : dependenciesFromPom) {
                final Artifact artifact = this.toArtifact(dependency);
                if (!isIncluded(artifact)) {
                    continue;
                }
                getLog().debug("dependency from pom: " + dependency.getGroupId() + ":" + dependency.getArtifactId()
                        + ":" + dependency.getVersion());
                if (dependency.getVersion() == null) {
                    // get parent and get the information from there.
                    if (getProject().hasParent()) {
                        getLog().debug("Reading parent dependencyManagement information");
                        if (getProject().getParent().getDependencyManagement() != null) {
                            List<Dependency> parentDeps =
                                    getProject().getParent().getDependencyManagement().getDependencies();
                            for (Dependency parentDep : parentDeps) {
                                // only groupId && artifactId needed cause version is null
                                if (dependency.getGroupId().equals(parentDep.getGroupId())
                                        && dependency.getArtifactId().equals(parentDep.getArtifactId())
                                        && dependency.getType().equals(parentDep.getType())) {
                                    dependencyManagement.add(parentDep);
                                }
                            }
                        }
                    } else {
                        String message = "We can't get the version for the dependency " + dependency.getGroupId() + ":"
                                + dependency.getArtifactId() + " cause there does not exist a parent.";
                        getLog().error(message);
                        // Throw error cause we will not able to get a version for a dependency.
                        throw new MojoExecutionException(message);
                    }
                } else {
                    dependencyManagement.add(dependency);
                }
            }
        }

        Set dependencies = new TreeSet(new DependencyComparator());
        for (Object dependency : getProject().getDependencies()) {
            if (dependency instanceof Dependency) {
                final Dependency dep = (Dependency) dependency;
                final Artifact artifact = this.toArtifact(dep);
                if (!isIncluded(artifact)) {
                    continue;
                }
                dependencies.add(dep);
            }
        }

        if (isProcessingDependencyManagement()) {
            dependencies = removeDependencyManagment(dependencies, dependencyManagement);
        }

        try {
            if (isProcessingDependencyManagement()) {
                logUpdates(getHelper().lookupDependenciesUpdates(dependencyManagement, false),
                        "Dependency Management");
            }
            if (isProcessingDependencies()) {
                logUpdates(getHelper().lookupDependenciesUpdates(dependencies, false), "Dependencies");
            }
        } catch (InvalidVersionSpecificationException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (ArtifactMetadataRetrievalException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void logUpdates(Map updates, String section) {
        List withUpdates = new ArrayList();
        List usingCurrent = new ArrayList();
        Iterator i = updates.values().iterator();
        while (i.hasNext()) {
            ArtifactVersions versions = (ArtifactVersions) i.next();
            String left = "  " + ArtifactUtils.versionlessKey(versions.getArtifact()) + " ";
            final String current;
            ArtifactVersion latest;
            if (versions.isCurrentVersionDefined()) {
                current = versions.getCurrentVersion().toString();
                latest = versions.getNewestUpdate(UpdateScope.ANY, Boolean.TRUE.equals(allowSnapshots));
            } else {
                ArtifactVersion newestVersion = versions.getNewestVersion(versions.getArtifact().getVersionRange(),
                        Boolean.TRUE.equals(allowSnapshots));
                current = versions.getArtifact().getVersionRange().toString();
                latest = newestVersion == null ? null
                        : versions.getNewestUpdate(newestVersion, UpdateScope.ANY,
                        Boolean.TRUE.equals(allowSnapshots));
                if (latest != null
                        && ArtifactVersions.isVersionInRange(latest, versions.getArtifact().getVersionRange())) {
                    latest = null;
                }
            }
            String right = " " + (latest == null ? current : current + " -> " + latest.toString());
            List t = latest == null ? usingCurrent : withUpdates;
            if (right.length() + left.length() + 3 > INFO_PAD_SIZE) {
                t.add(left + "...");
                t.add(StringUtils.leftPad(right, INFO_PAD_SIZE));

            } else {
                t.add(StringUtils.rightPad(left, INFO_PAD_SIZE - right.length(), ".") + right);
            }
        }
        if (isVerbose() && usingCurrent.isEmpty() && !withUpdates.isEmpty()) {
            logLine(false, String.format("[%s]: No dependencies in %s are using the newest version.", project.getArtifactId(), section));
            logLine(false, "");
        } else if (isVerbose() && !usingCurrent.isEmpty()) {
            logLine(false, String.format("[%s]: The following dependencies in %s are using the newest version:",  project.getArtifactId(), section));
            i = usingCurrent.iterator();
            while (i.hasNext()) {
                logLine(false, (String) i.next());
            }
            logLine(false, "");
        }
        if (withUpdates.isEmpty() && !usingCurrent.isEmpty()) {
            logLine(false, String.format("[%s]: No dependencies in %s have newer versions.", project.getArtifactId(), section));
            logLine(false, "");
        } else if (!withUpdates.isEmpty()) {
            logLine(false, String.format("[%s]: The following dependencies in %s have newer versions:",  project.getArtifactId(), section));
            i = withUpdates.iterator();
            while (i.hasNext()) {
                logLine(false, (String) i.next());
            }
            logLine(false, "");
        }
    }

    /**
     * @param pom the pom to update.
     * @throws org.apache.maven.plugin.MojoExecutionException when things go wrong
     * @throws org.apache.maven.plugin.MojoFailureException   when things go wrong in a very bad way
     * @throws javax.xml.stream.XMLStreamException            when things go wrong with XML streaming
     * @see org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo#update(org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader)
     * @since 1.0-alpha-1
     */
    protected void update(ModifiedPomXMLEventReader pom)
            throws MojoExecutionException, MojoFailureException, XMLStreamException {
        // do nothing
    }

}
