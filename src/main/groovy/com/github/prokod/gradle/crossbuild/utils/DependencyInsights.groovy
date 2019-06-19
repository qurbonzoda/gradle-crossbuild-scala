package com.github.prokod.gradle.crossbuild.utils

import com.github.prokod.gradle.crossbuild.CrossBuildPlugin
import com.github.prokod.gradle.crossbuild.ScalaVersionInsights
import com.github.prokod.gradle.crossbuild.ScalaVersions
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency

/**
 * A collection of Dependency related methods
 *
 */
class DependencyInsights {

    private final DependencyInsightsContext diContext

    DependencyInsights(DependencyInsightsContext diContext) {
        this.diContext = diContext
    }

    /**
     * The dependencySet is being searched for projects {@link Project}
     * that are used as a dependency of type {@link ProjectDependency}, which the cross build plugin
     * ({@link com.github.prokod.gradle.crossbuild.CrossBuildPlugin}) been applied on.
     * After being found, all the related (direct) dependencies for the specified configuration within those projects
     * are being returned.
     *
     * @param configurationName - configuration to have the dependencies extracted from
     * @return List of {@link Dependency} from relevant projects that are themselves defined as dependencies and share
     *          the same dependency graph with the ones originated from within initialDependencySet
     */
    List<Dependency> findAllCrossBuildProjectTypeDependenciesDependencies(String configurationName) {
        def projectTypeDependencies = extractCrossBuildProjectTypeDependencies()

        def dependenciesOfProjectDependencies = projectTypeDependencies.collectMany { prjDep ->
            extractCrossBuildProjectTypeDependencyDependencies(prjDep, configurationName)
        }

        dependenciesOfProjectDependencies
    }

    Set<Project> findAllCrossBuildPluginAppliedProjects() {
        def project = diContext.project
        def configuration = diContext.configurations?.current
        def parentConfiguration = diContext.configurations?.parent
        def moduleNames = project.gradle.rootProject.allprojects.findAll { it.plugins.hasPlugin(CrossBuildPlugin) }

        project.logger.debug(LoggerUtils.logTemplate(project,
                lifecycle:'afterEvaluate',
                configuration:configuration?.name,
                parentConfiguration:parentConfiguration?.name,
                msg:"Found the following crossbuild modules ${moduleNames.join(', ')}."))
        moduleNames
    }

    /**
     * Parses given dependency to its groupName:baseName part and its scala version part.
     * Throws Assertion Exception if dependency name does not contain separating char '_'
     *
     * @param dependency to parse
     * @return tuple in the form of (groupName:baseName, scalaVersion) i.e. ('group:lib', '2.11')
     */
    static boolean isScalaLib(Dependency dep) {
        def supposedlyScalaVersion = parseDependencyName(dep).second
        supposedlyScalaVersion != null
    }

    /**
     * Parses given dependency to its groupName:baseName part and its scala version part.
     * Throws Assertion Exception if dependency name does not contain separating char '_'
     *
     * @param dependency to parse
     * @return tuple in the form of (groupName:baseName, scalaVersion) i.e. ('group:lib', '2.11')
     */
    static Tuple2<String, String> parseDependencyName(Dependency dep) {
        def (baseName, supposedScalaVersion, nameSuffix) = parseDependencyName(dep.name)
        new Tuple2("${dep.group}:$baseName", supposedScalaVersion)
    }

    /**
     * Parses given dependency name to its baseName part and its scala version part.
     * returns the dependency name unparsed if dependency name does not contain separating char '_'
     *
     * @param depName dependency name to parse
     * @return tuple in the form of (baseName, scalaVersion, nameSuffix) i.e. ('lib', '2.11', '2.2.0')
     *         returns (name, {@code null}, {@code null}) otherwise.
     */
    static Tuple parseDependencyName(String name) {
        def index = name.indexOf('_')
        if (index > 0) {
            def baseName = name[0..index - 1]
            def supposedScalaVersionRaw = name[index + 1..-1]
            def innerIndex = supposedScalaVersionRaw.indexOf('_')
            def supposedScalaVersion = innerIndex > 0 ?
                    supposedScalaVersionRaw[0..innerIndex - 1] : supposedScalaVersionRaw
            def nameSuffix = innerIndex > 0 ? supposedScalaVersionRaw[innerIndex + 1..-1] : null
            new Tuple("$baseName", supposedScalaVersion, nameSuffix)
        } else {
            new Tuple(name, null, null)
        }
    }

    /**
     * Filters out all dependencies that match given 'scalaVersion' from a set of dependencies and then
     * enriches this list by converting each found dependency to a tuple containing itself and its counterparts.
     *
     * @param dependencies set of dependencies in the form of {@link org.gradle.api.artifacts.DependencySet} to scan
     * @param scalaVersion Scala Version to match against i.e '2.10', '2.11'
     * @return a list containing tuple2s  of tuple3s in the form of
     *         (groupName:baseArchiveName, scalaVersion, {@link org.gradle.api.artifacts.Dependency})
     *         For example, in case that scalaVersion = '2.10'
     *    [
     *     (('grp:lib', '2.11', ...), [('grp:lib', '2.10', ... version:'1.2'), ('grp:lib', '2.10', ... version:'1.2')]),
     *     (('grp:lib', '2.12', ...), [('grp:lib', '2.10', ... version:'1.2'), ('grp:lib', '2.10', ... version:'1.3')]),
     *     ((...), [(...), (...)]),
     *     ...
     *    ]
     */
    static List<Tuple2<Tuple, Set<Tuple>>> findAllNonMatchingScalaVersionDependenciesWithCounterparts(
            List<Dependency> dependencies,
            String scalaVersion) {
        def nonMatchingDeps = findAllNonMatchingScalaVersionDependencies(dependencies, scalaVersion)
        def dependenciesView = nonMatchingDeps.collect { nonMatchingDepTuple ->
            def matchingDepTupleSet = dependencies.collect { dep ->
                def (groupAndBaseName, supposedScalaVersion) = parseDependencyName(dep)
                new Tuple(groupAndBaseName, supposedScalaVersion, dep)
            }.findAll { it[0] == nonMatchingDepTuple[0] && it[1] != null && it[1] == scalaVersion }.collect().toSet()
            new Tuple2(nonMatchingDepTuple, matchingDepTupleSet)
        }

        dependenciesView
    }

    /**
     * Filters out all dependencies that match given 'scalaVersion' from a set of dependencies.
     * Excluding dependencies with scala version placeholder '_?'
     *
     * @param dependencySet set of dependencies in the form of {@link org.gradle.api.artifacts.DependencySet} to scan
     * @param scalaVersion Scala Version to un-match against i.e '2.10', '2.11'
     * @return a list of tuples in the form of
     *          (groupName:baseArchiveName, scalaVersion, {@link org.gradle.api.artifacts.Dependency})
     *          i.e. ('lib', '2.11', ...)
     */
    static List<Tuple> findAllNonMatchingScalaVersionDependenciesQMarksExcluded(
            List<Dependency> dependencySet,
            String scalaVersion) {
        findAllNonMatchingScalaVersionDependencies(dependencySet, scalaVersion).findAll { tuples ->
            tuples[1] != '?'
        }
    }

    static List<Tuple> findScalaDependencies(List<Dependency> dependencySet, ScalaVersions scalaVersions) {
        def scalaDeps = dependencySet
                .findAll { "${it.group}:${it.name}" == 'org.scala-lang:scala-library' }
                .collect { dep ->
            def scalaVersionInsights = new ScalaVersionInsights(dep.version, scalaVersions)
            def groupAndBaseName = parseDependencyName(dep).first
            new Tuple(groupAndBaseName, scalaVersionInsights.artifactInlinedVersion, dep) }

        scalaDeps
    }

    /**
     * Filters out all dependencies that match given 'scalaVersion' from a set of dependencies.
     *
     * @param dependencySet set of dependencies in the form of {@link org.gradle.api.artifacts.DependencySet} to scan
     * @param scalaVersion Scala Version to un-match against i.e '2.10', '2.11'
     * @return a list of tuples in the form of
     *          (baseName, scalaVersion, {@link org.gradle.api.artifacts.Dependency})
     *          i.e. ('lib', '2.11', ...)
     */
    static List<Tuple> findAllNonMatchingScalaVersionDependencies(List<Dependency> dependencySet, String scalaVersion) {
        def nonMatchingDeps = dependencySet.collect {
            def (groupAndBaseName, supposedScalaVersion) = parseDependencyName(it)
            new Tuple(groupAndBaseName, supposedScalaVersion, it) }
        .findAll { it[1] != null && it[1] != scalaVersion }

        nonMatchingDeps
    }

    static Closure isProjectDependency = { Dependency dependency -> dependency instanceof ProjectDependency }

    /**
     * The dependencySet is being searched for projects {@link Project}
     * that are used as a dependency of type {@link ProjectDependency}, which the cross build plugin
     * ({@link com.github.prokod.gradle.crossbuild.CrossBuildPlugin}) has been applied on.
     * After being found, The dependency graph is being searched for all the related (direct and transient) project
     * type dependencies for the specified configuration.
     *
     * NOTE: The outcome is dependent on the lifecycle stage this method is called in. It is complete and stable
     * after {@code gradle.projectsEvaluated} but changes after each {@code project.afterEvaluated}
     *
     * @return A set of {@link ProjectDependency} that belong to the dependency graph originated from the initial
     *         project type dependencies found in the initial dependency set
     */
    private Set<ProjectDependency> extractCrossBuildProjectTypeDependencies() {
        def modules = findAllCrossBuildPluginAppliedProjects()

        def initialDependencySet = diContext.dependencies
        def configuration = diContext.configurations.current
        def dependencies = extractCrossBuildProjectTypeDependenciesRecursively(modules, initialDependencySet.toSet(),
                configuration.name)

        dependencies
    }

    private Set<ProjectDependency> extractCrossBuildProjectTypeDependenciesRecursively(
            Set<Project> modules,
            Set<Dependency> inputDependencySet,
            String configurationName) {

        Set<ProjectDependency> accum = []

        def currentProjectTypDeps = inputDependencySet.findAll(isProjectDependency).findAll { isValid(it, modules) }
                .findAll { isNotAccumulated(it, accum) }.collect { (ProjectDependency) it }
        if (currentProjectTypDeps.size() > 0) {
            accum.addAll(currentProjectTypDeps)
            def currentProjectTypeDependenciesDependencies = currentProjectTypDeps.collectMany { prjDep ->
                extractCrossBuildProjectTypeDependencyDependencies(prjDep, configurationName)
            }
            accum.addAll(extractCrossBuildProjectTypeDependenciesRecursively(modules,
                    currentProjectTypeDependenciesDependencies.toSet(), configurationName))
        }

        accum
    }

    private static boolean isValid(Dependency dependency, Set<Project> modules) {
        modules*.name.contains(dependency.name)
    }

    private static boolean isNotAccumulated(Dependency dependency, Set<ProjectDependency> accum) {
        !accum*.name.contains(dependency.name)
    }

    private static Set<Dependency> extractCrossBuildProjectTypeDependencyDependencies(ProjectDependency dependency,
                                                                                      String configurationName) {
        def crossBuildProjectTypeDependencyDeps =
                dependency.dependencyProject.configurations.findByName(configurationName)?.allDependencies

        crossBuildProjectTypeDependencyDeps != null ? crossBuildProjectTypeDependencyDeps.toSet() : [] as Set
    }
}
