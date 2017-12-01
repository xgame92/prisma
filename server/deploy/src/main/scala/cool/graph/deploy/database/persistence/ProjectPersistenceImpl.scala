package cool.graph.deploy.database.persistence

import cool.graph.deploy.database.tables.{MigrationTable, ProjectTable, Tables}
import cool.graph.shared.models.{Migration, Project, UnappliedMigration}
import slick.jdbc.MySQLProfile.backend.DatabaseDef
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

case class ProjectPersistenceImpl(
    internalDatabase: DatabaseDef
)(implicit ec: ExecutionContext)
    extends ProjectPersistence {

  override def load(id: String): Future[Option[Project]] = {
    internalDatabase
      .run(ProjectTable.byIdWithMigration(id))
      .map(_.map { projectWithMigration =>
        DbToModelMapper.convert(projectWithMigration._1, projectWithMigration._2)
      })
  }

//  override def loadByIdOrAlias(idOrAlias: String): Future[Option[Project]] = {
//    internalDatabase
//      .run(ProjectTable.byIdOrAliasWithMigration(id))
//      .map(_.map { projectWithMigration =>
//        DbToModelMapper.convert(projectWithMigration._1, projectWithMigration._2)
//      })
//    internalDatabase
//      .run(ProjectTable.currentProjectByIdOrAlias(idOrAlias))
//      .map(_.map { projectRow =>
//        DbToModelMapper.convert(projectRow)
//      })
//  }

  override def save(project: Project): Future[Unit] = {
    val addProject = Tables.Projects += ModelToDbMapper.convert(project)
    internalDatabase.run(addProject).map(_ => ())
  }

  override def save(project: Project, migration: Migration): Future[Migration] = {
    for {
      latestMigration    <- internalDatabase.run(MigrationTable.lastMigrationForProject(migration.projectId))
      dbMigration        = ModelToDbMapper.convert(project, migration)
      withRevisionBumped = dbMigration.copy(revision = latestMigration.map(_.revision).getOrElse(0) + 1)
      addMigration       = Tables.Migrations += withRevisionBumped
      _                  <- internalDatabase.run(addMigration)
    } yield migration.copy(revision = withRevisionBumped.revision)
  }

  override def getUnappliedMigration(): Future[Option[UnappliedMigration]] = {
    for {
      unappliedMigrationOpt   <- internalDatabase.run(MigrationTable.getUnappliedMigration)
      projectWithMigrationOpt <- unappliedMigrationOpt.map(m => internalDatabase.run(ProjectTable.byIdWithMigration(m.projectId)))
    } yield {
      projectWithMigrationOpt.map(_.map { projectWithMigration =>
        unappliedMigrationOpt.map { migration =>
          val previousProject = DbToModelMapper.convert(projectWithMigration._1, projectWithMigration._2)
          val nextProject     = DbToModelMapper.convert(projectWithMigration._1, migration)
          val _migration      = DbToModelMapper.convert(migration)

          UnappliedMigration(previousProject, nextProject, _migration)
        }
      })
    }
  }

  override def markMigrationAsApplied(migration: Migration): Future[Unit] = {
    internalDatabase.run(MigrationTable.markAsApplied(migration.projectId, migration.revision)).map(_ => ())
  }
}