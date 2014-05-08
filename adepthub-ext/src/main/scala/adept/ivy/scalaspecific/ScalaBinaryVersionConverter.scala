package adept.ivy.scalaspecific

import adept.ivy.IvyImportResult
import adept.resolution.models.Id
import adept.repository.models.RepositoryName
import adept.repository.models.VariantHash
import adept.ext.Version
import adept.resolution.models.Constraint
import adept.ext.AttributeDefaults;
import adept.logging.Logging

import org.apache.ivy.core.module.descriptor.ExcludeRule

object ScalaBinaryVersionConverter extends Logging {
  val ScalaBinaryVersionRegex = """(.*)_(\d\..*?)(/.*)?""".r

  val scalaRepository = RepositoryName("org.scala-lang")
  val scalaLibIds = Set(
    Id("org.scala-lang/scala-library/config/compile"),
    Id("org.scala-lang/scala-library/config/default"),
    Id("org.scala-lang/scala-library/config/javadoc"),
    Id("org.scala-lang/scala-library/config/master"),
    Id("org.scala-lang/scala-library/config/provided"),
    Id("org.scala-lang/scala-library/config/runtime"),
    Id("org.scala-lang/scala-library/config/sources"),
    Id("org.scala-lang/scala-library/config/system"),
    Id("org.scala-lang/scala-library"))

  private def extractId(id: Id) = { //TODO: , exists: (RepositoryName, Id) => Boolean
    id.value match {
      case ScalaBinaryVersionRegex(newId, binaryVersion, rest) => //TODO: if exists(name, id) =>
        Id(newId + Option(rest).getOrElse(""))
      case _ => id
    }
  }

  private def convertIdsWithScalaBinaryVersion(name: RepositoryName, ids: Set[Id]) = {
    ids.map{ id =>
      extractId(id)
    }
  }

  def convertVersionInfoWithScalaBinaryVersion(versionInfo: Set[(RepositoryName, Id, Version)]): Set[(RepositoryName, Id, Version)] = {
    versionInfo.map {
      case (name, id, version) =>
        (name, extractId(id), version)
    }
  }

  private def convertExcludeRulesWithScalaBinaryVersion(excludeRules: Map[(Id, Id), Set[(String, String)]]): Map[(Id, Id), Set[(String, String)]] = {
    excludeRules.map {
      case ((id1, id2), excludes) =>
        ((extractId(id1), extractId(id2)), excludes)
    }
  }

  def convertResultWithScalaBinaryVersion(ivyImportResult: IvyImportResult): IvyImportResult = {
    val (id, maybeBinaryVersion) = ivyImportResult.variant.id.value match {
      case ScalaBinaryVersionRegex(newId, binaryVersion, rest) =>
        Id(newId + Option(rest).getOrElse("")) -> Some(binaryVersion) //rest is null if nothing matches
      case _ => ivyImportResult.variant.id -> None
    }
    maybeBinaryVersion match {
      case Some(binaryVersion) =>
        // We cannot be this strict because of sometimes you have a Id that does not depend on anything but still has _2.10  in its Id etc etc
        //        val convertible = ivyImportResult.versionInfo.exists {
        //          case (name, id, version) if exists(name, id) =>
        //            if (!version.asBinaryVersion.contains(binaryVersion))
        //              logger.warn("While converting using Scala binary versions we got a version (" + version + ") which does not contain the binary version expected: " + binaryVersion)
        //            name == scalaRepository && id == scalaLibId
        //        }
        val (scalaLibReqs, noScalaLibReqs) = ivyImportResult.variant.requirements.partition(r => scalaLibIds.contains(r.id))
        val hasAlreadyBinaryVersion = scalaLibReqs.exists(_.constraints.exists(_.name == AttributeDefaults.BinaryVersionAttribute))
        if (!hasAlreadyBinaryVersion) {
          val newReqs = noScalaLibReqs.map { requirement =>
            val newReqId = requirement.id.value match {
              case ScalaBinaryVersionRegex(newId, binaryVersion, rest) => //TODO: we should check if this one exists
                Id(newId + Option(rest).getOrElse(""))
              case _ => requirement.id
            }
            requirement.copy(id = newReqId)
          } ++ scalaLibReqs.map { requirement =>
            val newConstraints = requirement.constraints + Constraint(AttributeDefaults.BinaryVersionAttribute, Set(binaryVersion))
            requirement.copy(constraints = newConstraints)
          }
          val newVariant = ivyImportResult.variant.copy(
            id = id,
            requirements = newReqs)
          logger.debug("Adding scala library binary version: " + binaryVersion + " on ivy import of: " + ivyImportResult.variant)
          ivyImportResult.copy(
            variant = newVariant,
            excludeRules = convertExcludeRulesWithScalaBinaryVersion(ivyImportResult.excludeRules),
            extendsIds = convertIdsWithScalaBinaryVersion(ivyImportResult.repository, ivyImportResult.extendsIds),
            versionInfo = convertVersionInfoWithScalaBinaryVersion(ivyImportResult.versionInfo))
        } else {
          ivyImportResult
        }
      case None =>
        val newVariant = ivyImportResult.variant.copy(
          id = id,
          requirements = ivyImportResult.variant.requirements.map { requirement =>
            val newReqId = requirement.id.value match {
              case ScalaBinaryVersionRegex(newId, _, rest) => //TODO: we should check if this one exists
                Id(newId + Option(rest).getOrElse(""))
              case _ => requirement.id
            }
            val newExclusions = requirement.exclusions
            requirement.copy(id = newReqId)
          })
        ivyImportResult.copy(
          variant = newVariant,
          excludeRules = convertExcludeRulesWithScalaBinaryVersion(ivyImportResult.excludeRules),
          extendsIds = convertIdsWithScalaBinaryVersion(ivyImportResult.repository, ivyImportResult.extendsIds),
          versionInfo = convertVersionInfoWithScalaBinaryVersion(ivyImportResult.versionInfo))
    }
  }
}
