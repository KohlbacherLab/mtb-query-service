package de.dnpm.dip.mtb.query.api



import de.dnpm.dip.service.auth._
import de.dnpm.dip.service.query.QueryPermissions



object MTBPermissions extends QueryPermissions("MTB")


class MTBPermissionsSPI extends PermissionsSPI
{
  override def getInstance: Permissions =
    MTBPermissions
}



object MTBRoles extends Roles
{

  import MTBPermissions._


  val BasicMTBMember =
    Role(
      "BasicMTBMember",
      (permissions - ReadPatientRecord)
    )

  val PrivilegedMTBMember =
    Role(
      "PrivilegedMTBMember",
      permissions
    )

  override val roles: Set[Role] =
    Set(
      BasicMTBMember,
      PrivilegedMTBMember
    )

}


class MTBRolesSPI extends RolesSPI
{
  override def getInstance: Roles =
    MTBRoles
}

