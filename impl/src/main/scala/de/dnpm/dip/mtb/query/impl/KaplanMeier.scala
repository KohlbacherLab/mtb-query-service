package de.dnpm.dip.mtb.query.impl


import scala.util.chaining._
import java.time.{
  LocalDate,
  Instant,
  ZoneId
}
import java.time.temporal.ChronoUnit
import cats.{
  Applicative,
  Monad,
}
import de.dnpm.dip.coding.{
  Coding,
  CodeSystemProvider
}
import de.dnpm.dip.coding.atc.ATC
import de.dnpm.dip.coding.icd.{
  ICD,
  ICD10GM
}
import de.dnpm.dip.coding.icd.ClassKinds.Category
import de.dnpm.dip.service.query.{
  Count,
  Entry,
  ReportingOps
}
import de.dnpm.dip.model.{
  Snapshot,
  Id,
  ClosedInterval,
  Patient,
  Reference,
  Therapy,
  UnitOfTime
}
import Therapy.StatusReason.Progression
import de.dnpm.dip.mtb.model.{
  MTBPatientRecord,
  MTBMedicationTherapy,
  Response,
  RECIST
}
import de.dnpm.dip.mtb.query.api.PFSRatio
import de.dnpm.dip.mtb.query.api.KaplanMeier.{
  Config,
  SurvivalType,
  Grouping,
  DataPoint,
  CohortResult,
  SurvivalStatistics,
  SurvivalReport
}
import SurvivalType._
import Grouping._



trait KaplanMeierEstimator[F[_]]
{
  self =>

  def apply(
    input: Seq[(Long,Boolean)]
  )(
    implicit F: Monad[F]
  ): F[Seq[DataPoint]]


  def cohortResult(
    input: Seq[(Long,Boolean)]
  )(
    implicit F: Monad[F]
  ): F[CohortResult] = {

    import cats.syntax.functor._
    import cats.syntax.flatMap._

    // Median survival time defined as: min{ t | Surv(t) <= 0.5 }
    def medianSt(ts: Seq[DataPoint]): Long =
      ts.collectFirst { case DataPoint(t,surv,_,_) if surv <= 0.5 => t }
        .getOrElse(0L)


    for {
      surv <- self(input)
    } yield CohortResult(
      surv,
      medianSt(surv)
    )

  }

}


trait KaplanMeierModule[F[_]]
{
  self =>

  implicit val atc: CodeSystemProvider[ATC,cats.Id,Applicative[cats.Id]]

  implicit val icd10gm: CodeSystemProvider[ICD10GM,cats.Id,Applicative[cats.Id]]


  def survivalConfig: Config


  def survivalStatistics(
    survivalType: Option[SurvivalType.Value],
    grouping: Option[Grouping.Value],
//    survivalType: SurvivalType.Value,
//    grouping: Grouping.Value,
    cohort: Seq[Snapshot[MTBPatientRecord]],
    timeUnit: UnitOfTime = UnitOfTime.Weeks
  )(
    implicit
    estimator: KaplanMeierEstimator[F],
  ): F[SurvivalStatistics]


  def survivalReport(
    cohort: Seq[Snapshot[MTBPatientRecord]],
    timeUnit: UnitOfTime = UnitOfTime.Weeks,
    typesAndGroupings: Seq[(SurvivalType.Value,Grouping.Value)] =
      Seq(
        OS  -> Ungrouped,
        OS  -> ByTumorEntity,
        PFS -> ByTherapy
      )
  )(
    implicit
    estimator: KaplanMeierEstimator[F],
    F: Monad[F]
  ): F[SurvivalReport] = {

    import cats.syntax.traverse._
    import cats.syntax.functor._

//    val chronoUnit =
//      UnitOfTime.chronoUnit(timeUnit)

    typesAndGroupings.traverse { 
      case (survivalType,grouping) =>
        self.survivalStatistics(
          Some(survivalType),
          Some(grouping),
//          survivalType,
//          grouping,
          cohort,
          timeUnit
      )
    }

  }


  def pfsRatioReport(
    cohort: Seq[Snapshot[MTBPatientRecord]],
    timeUnit: UnitOfTime = UnitOfTime.Weeks
  )(
    implicit F: Monad[F]
  ): F[PFSRatio.Report]

}


class DefaultKaplanMeierModule(
  override implicit val atc: CodeSystemProvider[ATC,cats.Id,Applicative[cats.Id]],
  override implicit val icd10gm: CodeSystemProvider[ICD10GM,cats.Id,Applicative[cats.Id]]
)
extends KaplanMeierModule[cats.Id]
{

  import ATC.extensions._
  import ICD.extensions._


  override val survivalConfig: Config =
    Config(
      Seq(
        Entry(
          Coding(OS),
          Seq(Ungrouped,ByTumorEntity).map(Coding(_))
        ),
        Entry(
          Coding(PFS),
          Seq(ByTherapy).map(Coding(_))
        )
      )
    )


  override def survivalStatistics(
    survivalType: Option[SurvivalType.Value],
    grouping: Option[Grouping.Value],
//    survivalType: SurvivalType.Value,
//    grouping: Grouping.Value,
    cohort: Seq[Snapshot[MTBPatientRecord]],
    timeUnit: UnitOfTime
  )(
    implicit
    estimator: KaplanMeierEstimator[cats.Id]
  ): SurvivalStatistics = {

    val chronoUnit =
      UnitOfTime.chronoUnit(timeUnit)

    val survType = survivalType.getOrElse(PFS)
    val grping   = grouping.getOrElse(ByTherapy)

    cohort
      .flatMap(projectors(survType -> grping))
//      .flatMap(projectors(survivalType -> grouping))
      .groupMap(_._1){
        case (_,startDate,endDate,status) => chronoUnit.between(startDate,endDate) -> status
      }
      .map {
        case (group,data) =>
          Entry(
            group,
            estimator.cohortResult(data)
          )
      }
      .toSeq
      .sortBy(_.key)
      .pipe(
        SurvivalStatistics(
          Coding(survType),
          Coding(grping),
          timeUnit,
          _
        )
      )

  }


  private val progressionRecist =
    Set(
      RECIST.PD,
      RECIST.SD
    )
    .map(Coding(_))


  private val dateOfDeathOrCensoring: Snapshot[MTBPatientRecord] => (LocalDate,Boolean) = {

    case Snapshot(record,t) =>
    
      record
        .patient
        .dateOfDeath
        .map(_ -> true)
        .getOrElse(
          // 1. Censoring time strategy: fall back to date of last therapy follow-up
          record
            .getMedicationTherapies
            .flatMap(_.history.map(_.recordedOn))
            .maxOption
            // 2. Censoring time strategy: fall back to upload date
            .getOrElse(LocalDate.ofInstant(Instant.ofEpochMilli(t),ZoneId.systemDefault)) -> false
          )

  }


  private def progressionOrCensoringDate(
    therapy: MTBMedicationTherapy,
    patient: Patient
  )(
    implicit lastResponses: Map[Id[MTBMedicationTherapy],Response]
  ): (LocalDate,Boolean) =
    lastResponses
      .get(therapy.id)
      // 1. Look for date of latest response with recorded progression
      .collect {
        case response if progressionRecist contains response.value =>
          response.effectiveDate
      }
      // 2. Check whether therapy was stopped due to progression and take the end or recording date
      .orElse(
        therapy
          .statusReason
          .collect { 
            case Therapy.StatusReason(Progression) =>
              therapy.period
                .flatMap(_.endOption)
                .getOrElse(therapy.recordedOn)
          }
      )
      // 3. Use patient date of death as "progression" date
      .orElse(patient.dateOfDeath)
      .map(_ -> true)
      // 4. Censoring: therapy recording date
      .getOrElse(therapy.recordedOn -> false)
  

  private val projectors: Map[
    (SurvivalType.Value,Grouping.Value),
    Snapshot[MTBPatientRecord] => Iterable[(String,LocalDate,LocalDate,Boolean)]
  ] =
    Map(
      (OS,ByTumorEntity) -> {
        snp =>
          val (observationDate,status) = dateOfDeathOrCensoring(snp)
        
          snp.data
            .getDiagnoses
            .flatMap(
              diagnosis =>
                diagnosis
                  .recordedOn
                  .map(
                    diagDate =>
                      (
                        diagnosis.code
                          .parentOfKind(Category)
                          .getOrElse(diagnosis.code)
                          .code.value,
                        diagDate,
                        observationDate,
                        status
                      )
                    )
            )

      },
      (OS,Ungrouped) -> {
        snp =>
          val (observationDate,status) = dateOfDeathOrCensoring(snp)

          snp.data
            .getDiagnoses
            .flatMap(_.recordedOn)
            .minOption
            .map(
              date =>
                (
                  "Alle",
                  date,
                  observationDate,
                  status
                )
            )
      },
      (PFS,ByTherapy) -> { 
        case Snapshot(record,_) =>
        
          implicit val lastResponses =
            record
              .getResponses
              .groupBy(_.therapy)
              .collect {
//                case (ref,responses) if ref.id.isDefined =>
//                  ref.id.get -> responses.maxBy(_.effectiveDate)
                case (Reference(Some(therapyId),_,_,_),responses) =>
                  therapyId -> responses.maxBy(_.effectiveDate)
              }
          
          record
            .getMedicationTherapies
            .flatMap(_.latest)
            .flatMap {
              therapy =>
          
                val (observationDate,status) =
                  progressionOrCensoringDate(therapy,record.patient)

                for { 
                  start <-
                    therapy.period.map(_.start) 
          
                  medClasses <-
                    therapy
                      .medication
                      .map(_.flatMap(_.currentGroup))
                      .map(_.flatMap(_.display))
          
                } yield (
                  medClasses.mkString(" + "),
                  start,
                  observationDate,
                  status
                )
          
            }
      },
      (PFS,Ungrouped) -> { 
        case Snapshot(record,_) =>
        
          implicit val lastResponses =
            record
              .getResponses
              .groupBy(_.therapy)
              .collect {
                case (Reference(Some(therapyId),_,_,_),responses) =>
                  therapyId -> responses.maxBy(_.effectiveDate)
              }
          
          record
            .getMedicationTherapies
            .flatMap(_.latest)
            .flatMap {
              therapy =>
          
                val (observationDate,status) =
                  progressionOrCensoringDate(therapy,record.patient)

                therapy.period
                  .map(_.start) 
                  .map(date =>
                    (
                      "Alle",
                      date,
                      observationDate,
                      status
                    )
                  )
                     
          }
      }

    )

  override def pfsRatioReport(
    cohort: Seq[Snapshot[MTBPatientRecord]],
    timeUnit: UnitOfTime = UnitOfTime.Weeks
  )(
    implicit 
    F: Monad[cats.Id]
  ): PFSRatio.Report = 
    Seq(
      Entry(
        "Alle",
        cohort
          .flatMap(snp =>
            pfsRatio(
              snp.data,
              UnitOfTime.chronoUnit(timeUnit)
            )
          )
          .pipe(_.sortBy(_.pfsr))
          .pipe {
            dataPoints =>

              PFSRatio.CohortResult(
                timeUnit,
                dataPoints.zipWithIndex
                  .map {
                    case (dp,idx) => dp.copy(patient = s"Patient $idx")
                  },
                ReportingOps.median[Double].apply(dataPoints.map(_.pfsr)),
                Count.of(
                  n     = dataPoints.count(_.pfsr >= 1.3),
                  total = dataPoints.size
                )
              )

          }
      )
    )


  private def pfsRatio(
    record: MTBPatientRecord,
    chronoUnit: ChronoUnit
  ): Option[PFSRatio.DataPoint] = {

    def progressionTime(
      therapy: MTBMedicationTherapy,
      patient: Patient
    )(
      implicit lastResponses: Map[Id[MTBMedicationTherapy],Response]
    ): Option[Long] = {
      val (observationDate,status) =
        progressionOrCensoringDate(therapy,record.patient)

      status match {
        case true  =>
          therapy.period
            .map(p => chronoUnit.between(p.start,observationDate))
        case false => None
      }
    }

    implicit val lastResponses =
      record
        .getResponses
        .groupBy(_.therapy)
        .collect {
          case (Reference(Some(therapyId),_,_,_),responses) =>
            therapyId -> responses.maxBy(_.effectiveDate)
        }
   
    for {
      pfs1 <-
        record
          .getGuidelineMedicationTherapies
          .maxByOption(_.recordedOn)
          .flatMap(progressionTime(_,record.patient))

      pfs2 <-
        record
          .getMedicationTherapies
          .flatMap(_.latest)         // Take latest entry of each therapy history...
          .maxByOption(_.recordedOn) // ... then take the latest recorded therapy of all
          .flatMap(progressionTime(_,record.patient))

    } yield PFSRatio.DataPoint(
      record.patient.id.value,  // TODO: 
      pfs1,
      pfs2,
      (pfs2.toDouble/pfs1)
    )
          
  }

}



object DefaultKaplanMeierEstimator extends KaplanMeierEstimator[cats.Id]
{

  import scala.math.sqrt


  private val z =
    1.96 // z-Factor for 95% confidence interval


  override def apply(
    input: Seq[(Long,Boolean)]
  )(
    implicit F: Monad[cats.Id]
  ): Seq[DataPoint] = {

    val statusByTime =
      input
        .groupMap(_._1)(_._2) // Group input entries by serial time 
        .toSeq
        .sortBy(_._1)         // then sort by time to Seq[(Long,Seq[Boolean])]

    statusByTime
      .foldLeft(
        (
          Seq(
            DataPoint(
              0L,                     // t = 0
              1.0,                    // survival rate at t = 0 is 1.0 by definition
              false,                  // no censored entries at t = 0
              ClosedInterval(1.0,1.0) // std error vanishes at t = 0
            ),
          ),
          0.0  // accumulator for variance sum: Sum_i=1^j{di/(ni*(ni - di))}
        )
      ){
        case ((dataPoints,varAcc),(t,eventStatus)) =>

          // num of events at t
          val d =
            eventStatus.count(_ == true)

          // num of "patients at risk" at and after this time
          val n =
            statusByTime
              .dropWhile(_._1 < t)
              .map(_._2.size)
              .sum 

          val st =
            dataPoints.last.survRate * (1.0 - d.toDouble/n)

          // At the last data point, n = d if no event is censored,
          // which would lead to division by 0 in the sum entering into the variance.
          // But given that the above survival rate st becomes 0 due to d/n = 1, thus also the std error,
          // avoid NaN issues by skipping this uninformative term in the sum
          val varianceSum =
            if (d != n) varAcc + d.toDouble/(n*(n - d))
            else varAcc 

          val stdErr =
            st * sqrt(varianceSum)

          (
            dataPoints :+ DataPoint(
              t,
              st,
              eventStatus.forall(_ == false),
              ClosedInterval(   // Greenwood method for the confidence interval
                st - z*stdErr,
                st + z*stdErr,
              )
            ),
            varianceSum
          )

      }
      ._1

  }

}
