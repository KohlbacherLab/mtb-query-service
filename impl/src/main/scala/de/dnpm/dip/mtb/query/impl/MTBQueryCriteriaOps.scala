package de.dnpm.dip.mtb.query.impl


import scala.util.chaining._
import cats.data.Ior
import cats.data.Ior.{
  Left,
  Right,
  Both
}
import de.dnpm.dip.coding.{
  Code,
  Coding
}
import de.dnpm.dip.coding.atc.ATC
import de.dnpm.dip.coding.hgvs.HGVS
import de.dnpm.dip.model.Reference
import de.dnpm.dip.mtb.model.{
  MTBPatientRecord,
  MTBMedicationRecommendation,
  SNV,
  CNV,
  Variant
}
import de.dnpm.dip.mtb.query.api._



private trait MTBQueryCriteriaOps
{

  private[impl] implicit class Extensions(criteria: MTBQueryCriteria){

    def isEmpty: Boolean =
      (
        criteria.getDiagnoses          ++
        criteria.getTumorMorphologies  ++
        criteria.getSimpleVariants     ++
        criteria.getCopyNumberVariants ++
        criteria.getDnaFusions         ++
        criteria.getRnaFusions         ++
        criteria.getResponses          ++
        criteria.getDrugs
      )
      .isEmpty

    def nonEmpty = !criteria.isEmpty


    def intersect(other: MTBQueryCriteria): MTBQueryCriteria =
      MTBQueryCriteria(
        criteria.diagnoses.map(_ intersect other.getDiagnoses),
        criteria.tumorMorphologies.map(_ intersect other.getTumorMorphologies),
        criteria.simpleVariants.map(_ intersect other.getSimpleVariants),
        criteria.copyNumberVariants.map(_ intersect other.getCopyNumberVariants),
        criteria.dnaFusions.map(_ intersect other.getDnaFusions),
        criteria.rnaFusions.map(_ intersect other.getRnaFusions),
        criteria.medication.map(
          med =>
            other.medication match {
              case Some(MedicationCriteria(_,drugs,_)) if drugs.nonEmpty => 
                med.copy(
                  drugs = med.drugs intersect drugs
                )
              case _ => 
                med
            }
        ),
        criteria.responses.map(_ intersect other.getResponses),
      )

    def &(other: MTBQueryCriteria) = criteria intersect other

  }


  private def checkMatches(
    bs: Boolean*
  )(
    strict: Boolean
  ): Boolean =
    if (strict)
      bs forall (_ == true)
    else
      bs exists (_ == true)


  private def matches[T](
    criteria: Option[Set[T]],
    values: => Set[T]
  ): (Option[Set[T]],Boolean) =
    criteria match {
      case Some(set) if set.nonEmpty =>
        (set intersect values)
          .pipe {
            case matches if matches.nonEmpty =>
              Some(matches) -> true

            case _ =>
              None -> false
          }

      case _ => None -> true
    }


  private def matches[T](
    criterion: Option[T],
    value: Option[T]
  ): Boolean =
    criterion
      .map(c => value.exists(_ == c))
      .getOrElse(true)



  import scala.language.implicitConversions

  implicit def optBooleantoBoolean(opt: Option[Boolean]): Boolean =
    opt.getOrElse(false)


  private def snvsMatch(
    criteria: Option[Set[SNVCriteria]],
    snvs: => Seq[SNV]
  )(
    implicit supportingVariants: Seq[Reference[Variant]]
  ): (Option[Set[SNVCriteria]],Boolean) = {

    import HGVS.extensions._

    criteria match {
      case Some(set) if set.nonEmpty =>
        set.filter {
          case SNVCriteria(gene,dnaChange,proteinChange,supporting) =>
            snvs.find(
              snv =>
                checkMatches(
                  matches(gene,snv.gene),
                  dnaChange.map(g => snv.dnaChange.exists(_ matches g)).getOrElse(true),
                  proteinChange.map(g => snv.proteinChange.exists(_ matches g)).getOrElse(true)
                )(
                  true
                ) 
            )
            .exists { 
              case snv if supporting => supportingVariants.exists(_.id.exists(_ == snv.id))
              case _ => true 
            }
            
        }
        .pipe {
          case matches if matches.nonEmpty =>  
            Some(matches) -> true

          case _ =>
            None -> false
        }

      case _ => None -> true
    }

  }

  private def cnvsMatch(
    criteria: Option[Set[CNVCriteria]],
    cnvs: => Seq[CNV]
  )(
    implicit supportingVariants: Seq[Reference[Variant]]
  ): (Option[Set[CNVCriteria]],Boolean) =
    criteria match {
      case Some(set) if set.nonEmpty =>
        set.filter {
          case CNVCriteria(affectedGenes,typ,supporting) =>
            cnvs.find(
              cnv =>
                checkMatches(
                  affectedGenes match {
                    case Some(genes) if genes.nonEmpty => cnv.reportedAffectedGenes.exists(_.intersect(genes).nonEmpty)
                    case _ => true
                  },
                  typ.map(_ == cnv.`type`).getOrElse(true)
                )(
                  true
                ) 
              )
              .exists { 
                case snv if supporting => supportingVariants.exists(_.id.exists(_ == snv.id))
                case _ => true 
              }
        }
        .pipe {
          case matches if matches.nonEmpty =>  
            Some(matches) -> true

          case _ =>
            None -> false
        }

      case _ => None -> true
    }


  private def medicationsMatch(
    criteria: Option[MedicationCriteria],
    recommendedDrugs: => Set[Coding[ATC]],
    usedDrugs: => Set[Coding[ATC]],
  ): (Option[MedicationCriteria],Boolean) = {

    import MedicationUsage._
    import LogicalOperator.{And,Or}

    criteria match {
      case Some(MedicationCriteria(op,selectedDrugs,usage)) if selectedDrugs.nonEmpty => 
        usage
          .collect { case MedicationUsage(value) => value }
          .pipe {
            case s if s.contains(Recommended) && s.contains(Used) => recommendedDrugs & usedDrugs
            case s if s.contains(Recommended)                     => usedDrugs
            case s if s.contains(Used)                            => usedDrugs
            case s                                                => recommendedDrugs | usedDrugs
          }
          .pipe {
            _.flatMap(_.display).map(_.toLowerCase)
          }
          .pipe {
            drugNames =>

              op.getOrElse(Or) match {

                case Or =>
                  selectedDrugs.filter( 
                    coding => drugNames.exists(name => coding.display.exists(name contains _.toLowerCase))
                  )

                case And =>
                  selectedDrugs.forall( 
                    coding => drugNames.exists(name => coding.display.exists(name contains _.toLowerCase))
                  ) match {
                    case true  => selectedDrugs
                    case false => Set.empty[Coding[ATC]]
                  }
 
              }
          }
          .pipe {
            case matches if matches.nonEmpty =>
              Some(MedicationCriteria(op,matches,usage)) -> true
            case _ =>
              None  -> false
          }

      case _ => None -> true
    }

  }


  def criteriaMatcher(
    strict: Boolean = true
  ): MTBQueryCriteria => (MTBPatientRecord => Option[MTBQueryCriteria]) = {

     _ match {

        // If criteria object is empty, i.e. no query criteria are defined at all, any patient record matches
        case criteria if criteria.isEmpty => 
          record => Some(criteria)

          
        case criteria => 

          record =>

            implicit lazy val supportingVariants =
              record.getCarePlans
                .flatMap(_.medicationRecommendations.getOrElse(List.empty))
                .flatMap(_.supportingVariants.getOrElse(List.empty)) 

            val (diagnosisMatches, diagnosesFulfilled) =
              matches(
                criteria.diagnoses,
                record.getDiagnoses
                  .map(_.code)
                  .toSet
              )

            val (morphologyMatches, morphologyFulfilled) =
              matches(
                criteria.tumorMorphologies,
                record.getHistologyReports
                  .flatMap(_.results.tumorMorphology)
                  .map(_.value)
                  .toSet
              )

            val (snvMatches, snvsFulfilled) =
              snvsMatch(
                criteria.simpleVariants,
                record
                  .getNgsReports
                  .flatMap(_.results.simpleVariants)
              )

            val (cnvMatches, cnvsFulfilled) =
              cnvsMatch(
                criteria.copyNumberVariants,
                record.getNgsReports
                  .flatMap(_.results.copyNumberVariants)
              )

            val (medicationMatches, medicationFulfilled) =
              medicationsMatch(
                criteria.medication,
                record.getCarePlans
                  .flatMap(_.medicationRecommendations.getOrElse(List.empty))
                  .flatMap(_.medication)
                  .toSet[Coding[ATC]],
                record.getMedicationTherapies
                  .map(_.latest)
                  .flatMap(_.medication.getOrElse(Set.empty))
                  .toSet[Coding[ATC]]
              )


            val (responseMatches, responseFulfilled) =
              matches(
                criteria.responses,
                record.getResponses
                  .map(_.value)
                  .toSet
              )

          if (
            checkMatches(
              diagnosesFulfilled,
              morphologyFulfilled,
              snvsFulfilled,
              cnvsFulfilled,
              //TODO: DNA-/RNA-Fusions
              medicationFulfilled,
              responseFulfilled
            )(
              strict
            )
          )
            Some(
              MTBQueryCriteria(
                diagnosisMatches,
                morphologyMatches,
                snvMatches,
                cnvMatches,
                None, //TODO: DNA-Fusions
                None, //TODO: RNA-Fusions
                medicationMatches,
                responseMatches,
              )
            )
          else 
            None
      }
  }

}

private object MTBQueryCriteriaOps extends MTBQueryCriteriaOps

