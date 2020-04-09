/*
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.build.finder.core;

import static org.jboss.pnc.build.finder.core.AnsiUtils.green;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.pnc.build.finder.koji.KojiBuild;
import org.jboss.pnc.build.finder.pnc.EnhancedArtifact;
import org.jboss.pnc.build.finder.pnc.PncBuild;
import org.jboss.pnc.build.finder.pnc.client.PncClient;
import org.jboss.pnc.build.finder.pnc.client.PncUtils;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.client.RemoteResourceNotFoundException;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.enums.ArtifactQuality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;

import io.vertx.core.impl.ConcurrentHashSet;

/**
 * Build Finder for PNC
 *
 * @author Jakub Bartecek
 */
public class PncBuildFinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PncBuildFinder.class);

    // TODO make the parallelismThreashold configurable
    private final int concurrentMapParallelismThreshold = 10;

    private final PncClient pncClient;

    private final BuildFinderUtils buildFinderUtils;

    public PncBuildFinder(PncClient pncClient, BuildFinderUtils buildFinderUtils) {
        this.pncClient = pncClient;
        this.buildFinderUtils = buildFinderUtils;
    }

    public Map<BuildSystemInteger, KojiBuild> findBuildsPnc(Map<Checksum, Collection<String>> checksumTable)
            throws RemoteResourceException {
        if (checksumTable == null || checksumTable.isEmpty()) {
            LOGGER.warn("Checksum table is empty");
            return Collections.emptyMap();
        }

        Set<EnhancedArtifact> artifacts = lookupArtifactsInPnc(new ConcurrentHashMap<>(checksumTable));

        ConcurrentHashMap<String, PncBuild> pncBuilds = groupArtifactsAsPncBuilds(artifacts);

        populatePncBuildsMetadata(pncBuilds);

        return convertPncBuildsToKojiBuilds(pncBuilds);
    }

    private Map<BuildSystemInteger, KojiBuild> convertPncBuildsToKojiBuilds(Map<String, PncBuild> pncBuilds) {
        Map<BuildSystemInteger, KojiBuild> foundBuilds = new HashMap<>();

        pncBuilds.values().forEach((pncBuild -> {

            if (isBuildZero(pncBuild)) {
                KojiBuild kojiBuild = convertPncBuildZeroToKojiBuild(pncBuild);
                foundBuilds.put(new BuildSystemInteger(0, BuildSystem.none), kojiBuild);
            } else {
                KojiBuild kojiBuild = convertPncBuildToKojiBuild(pncBuild);
                foundBuilds.put(new BuildSystemInteger(kojiBuild.getBuildInfo().getId(), BuildSystem.pnc), kojiBuild);
            }
        }));

        return foundBuilds;
    }

    private void populatePncBuildsMetadata(ConcurrentHashMap<String, PncBuild> pncBuilds)
            throws RemoteResourceException {
        final RemoteResourceExceptionWrapper exceptionWrapper = null;

        pncBuilds.forEach(concurrentMapParallelismThreshold, (buildId, pncBuild) -> {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Parallel execution of populatePncBuildsMetadata using thread "
                                + Thread.currentThread().getName() + "of build " + pncBuild);
            }
            Build build = pncBuild.getBuild();

            // Skip build with id 0, which is just a container for not found artifacts
            if (!isBuildZero(pncBuild)) {
                try {
                    pncBuild.setProductVersion(
                            Optional.of(pncClient.getProductVersion(build.getProductMilestone().getId())));
                } catch (RemoteResourceNotFoundException e) {
                    // NOOP - keep the field empty
                } catch (RemoteResourceException e) {
                    exceptionWrapper.setException(e);
                }

                try {
                    pncBuild.setBuildPushResult(Optional.of(pncClient.getBuildPushResult(build.getId())));
                } catch (RemoteResourceNotFoundException e) {
                    // NOOP - keep the field empty
                } catch (RemoteResourceException e) {
                    exceptionWrapper.setException(e);
                }
            }
        });

        if (exceptionWrapper.getException() != null) {
            throw exceptionWrapper.getException();
        }
    }

    private Set<EnhancedArtifact> lookupArtifactsInPnc(ConcurrentHashMap<Checksum, Collection<String>> checksumTable)
            throws RemoteResourceException {
        Set<EnhancedArtifact> artifacts = new ConcurrentHashSet<>();
        final RemoteResourceExceptionWrapper exceptionWrapper = null;

        checksumTable.forEach(concurrentMapParallelismThreshold, (checksum, fileNames) -> {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Parallel execution of lookupArtifactsInPnc using thread " + Thread.currentThread().getName()
                                + " of an artifact with checksum: " + checksum);
            }

            try {
                EnhancedArtifact enhancedArtifact = new EnhancedArtifact(
                        findArtifactInPnc(checksum, fileNames),
                        checksum,
                        fileNames);
                artifacts.add(enhancedArtifact);
            } catch (RemoteResourceException e) {
                exceptionWrapper.setException(e);
            }
        });

        if (exceptionWrapper.getException() != null) {
            throw exceptionWrapper.getException();
        }

        return artifacts;
    }

    /**
     * A build produces multiple artifacts. This methods associates all the artifacts to the one PncBuild
     *
     * @param artifacts All found artifacts
     * @return A map pncBuildId,pncBuild
     */
    private ConcurrentHashMap<String, PncBuild> groupArtifactsAsPncBuilds(Set<EnhancedArtifact> artifacts) {
        ConcurrentHashMap<String, PncBuild> pncBuilds = new ConcurrentHashMap<>();
        Build buildZero = Build.builder().id("0").build();

        artifacts.forEach((artifact) -> {
            Build build;

            if (artifact.getArtifact().isPresent() && artifact.getArtifact().get() != null) {
                build = artifact.getArtifact().get().getBuild();
            } else {
                // Covers 2 cases:
                // 1) An Artifact stored in PNC DB, which was not built in PNC
                // Such artifacts are treated the same way as artifacts not found in PNC
                // 2) Artifact was not found in PNC
                // Such artifacts will be associated in a build with ID 0
                build = buildZero;
            }

            if (pncBuilds.containsKey(build.getId())) {
                pncBuilds.get(build.getId()).getArtifacts().add(artifact);
            } else {
                PncBuild pncBuild = new PncBuild(build);
                pncBuild.getArtifacts().add(artifact);
                pncBuilds.put(build.getId(), pncBuild);
            }
        });

        return pncBuilds;
    }

    /**
     * Lookups an Artifact in PNC and chooses the best candidate
     *
     * @param checksum A checksum
     * @param fileNames List of filenames
     * @return Artifact found in PNC or Optional.empty() if not found
     * @throws RemoteResourceException Thrown if a problem in communication with PNC occurs
     */
    private Optional<Artifact> findArtifactInPnc(Checksum checksum, Collection<String> fileNames)
            throws RemoteResourceException {
        if (buildFinderUtils.shouldSkipChecksum(checksum, fileNames)) {
            LOGGER.debug("Skipped checksum {} for fileNames {}", checksum, fileNames);
            return null;
        }
        LOGGER.debug("PNC: checksum={}", checksum);

        // Lookup Artifacts and associated builds in PNC
        Collection<Artifact> artifacts = lookupPncArtifactsByChecksum(checksum);
        if (artifacts == null || artifacts.isEmpty()) {
            return Optional.empty();
        }

        return getBestPncArtifact(artifacts);
    }

    private Collection<Artifact> lookupPncArtifactsByChecksum(Checksum checksum) throws RemoteResourceException {
        switch (checksum.getType()) {
            case md5:
                return pncClient.getArtifactsByMd5(checksum.getValue()).getAll();

            case sha1:
                return pncClient.getArtifactsBySha1(checksum.getValue()).getAll();

            case sha256:
                return pncClient.getArtifactsBySha256(checksum.getValue()).getAll();

            default:
                throw new IllegalArgumentException(
                        "Unsupported checksum type requested! " + "Checksum type " + checksum.getType()
                                + " is not supported.");
        }
    }

    private int getArtifactQuality(Object obj) {
        Artifact a = (Artifact) obj;
        ArtifactQuality quality = a.getArtifactQuality();

        switch (quality) {
            case NEW:
                return 1;
            case VERIFIED:
                return 2;
            case TESTED:
                return 3;
            case DEPRECATED:
                return -1;
            case BLACKLISTED:
                return -2;
            case TEMPORARY:
                return -3;
            case DELETED:
                return -4;
            default:
                LOGGER.warn("Unsupported ArtifactQuality! Got: " + quality);
                return -100;
        }
    }

    private Optional<Artifact> getBestPncArtifact(Collection<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            throw new IllegalArgumentException("No artifacts provided!");
        }

        if (artifacts.size() == 1) {
            return Optional.of(artifacts.iterator().next());
        } else {
            return Optional.of(
                    artifacts.stream()
                            .sorted(Comparator.comparing(this::getArtifactQuality).reversed())
                            .filter(artifact -> artifact.getBuild() != null)
                            .findFirst()
                            .orElse(artifacts.iterator().next()));
        }
    }

    private KojiBuild convertPncBuildToKojiBuild(PncBuild pncBuild) {
        KojiBuild kojibuild = PncUtils.pncBuildToKojiBuild(pncBuild);

        for (EnhancedArtifact artifact : pncBuild.getArtifacts()) {

            KojiArchiveInfo kojiArchive = PncUtils.artifactToKojiArchiveInfo(pncBuild, artifact.getArtifact().get());
            PncUtils.fixNullVersion(kojibuild, kojiArchive);
            buildFinderUtils.addArchiveToBuild(kojibuild, kojiArchive, artifact.getFilenames());

            LOGGER.info(
                    "Found build in Pnc: id: {} nvr: {} checksum: ({}) {} archive: {}",
                    green(pncBuild.getBuild().getId()),
                    green(PncUtils.getNVRFromBuildRecord(pncBuild.getBuild())),
                    green(artifact.getChecksum().getType()),
                    green(artifact.getChecksum().getValue()),
                    green(artifact.getFilenames()));
        }

        return kojibuild;
    }

    private KojiBuild convertPncBuildZeroToKojiBuild(PncBuild pncBuild) {
        KojiBuild kojiBuild = buildFinderUtils.createKojiBuildZero();

        pncBuild.getArtifacts().forEach((enhancedArtifact -> {
            buildFinderUtils
                    .addArchiveWithoutBuild(kojiBuild, enhancedArtifact.getChecksum(), enhancedArtifact.getFilenames());
        }));

        return kojiBuild;
    }

    /**
     * Check if PncBuild.build.id has "0" value, which indicates a container for not found artifacts
     *
     * @param pncBuild A PncBuild
     * @return False if the build has ID 0 otherwise true
     */
    private boolean isBuildZero(PncBuild pncBuild) {
        return "0".equals(pncBuild.getBuild().getId());
    }

    private class RemoteResourceExceptionWrapper {
        private RemoteResourceException exception;

        public RemoteResourceException getException() {
            return exception;
        }

        public synchronized void setException(RemoteResourceException exception) {
            this.exception = exception;
        }
    }
}
