package im.actor.server.api.rpc.service.contacts

import java.security.MessageDigest

import scala.collection.immutable
import scala.concurrent._
import scala.concurrent.duration._

import akka.actor._
import akka.util.Timeout
import scodec.bits.BitVector
import slick.dbio.DBIO
import slick.driver.PostgresDriver.api._

import im.actor.api.rpc._
import im.actor.api.rpc.contacts._
import im.actor.api.rpc.misc._
import im.actor.api.rpc.users.{ UpdateUserLocalNameChanged, User }
import im.actor.server.api.util
import im.actor.server.api.util.{ PhoneNumber, UserUtils }
import im.actor.server.{ models, persist }

class ContactsServiceImpl(seqUpdManagerRegion: ActorRef)
                         (implicit
                          db: Database,
                          actorSystem: ActorSystem)
  extends ContactsService {

  import im.actor.server.push.SeqUpdatesManager._

  override implicit val ec: ExecutionContext = actorSystem.dispatcher
  implicit val timeout = Timeout(5.seconds)

  object Errors {
    val CantAddSelf = RpcError(401, "OWN_USER_ID", "User id cannot be equal to self.", false, None)
    val ContactAlreadyExists = RpcError(400, "CONTACT_ALREADY_EXISTS", "Contact already exists.", false, None)
    val ContactNotFound = RpcError(404, "CONTACT_NOT_FOUND", "Contact not found.", false, None)
  }

  private[service] def hashIds(ids: Seq[Int]): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val uids = ids.to[immutable.SortedSet].mkString(",")
    BitVector(md.digest(uids.getBytes)).toHex
  }

  override def jhandleImportContacts(phones: Vector[PhoneToImport], emails: Vector[EmailToImport], clientData: ClientData): Future[HandlerResult[ResponseImportContacts]] = {
    val action = requireAuth(clientData).map { implicit client =>
      // TODO: flatten

      persist.UserPhone.findByUserId(client.userId).head.flatMap { currentUserPhone =>
        persist.User.find(client.userId).head.flatMap { currentUser =>
          val filteredPhones = phones.filterNot(_.phoneNumber == currentUserPhone.number)
          val phoneNumbers = filteredPhones.map(_.phoneNumber).map(PhoneNumber.normalizeLong(_, currentUser.countryCode)).flatten.toSet
          val phonesMap = immutable.HashMap(filteredPhones.map { p => p.phoneNumber -> p.name }: _*)

          val f = for {
            userPhones <- persist.UserPhone.findByNumbers(phoneNumbers)
            ignoredContactsIds <- persist.contact.UserContact.findIds_all(client.userId)
            uniquePhones = userPhones.filter(p => !ignoredContactsIds.contains(p.userId))
            usersPhones <- DBIO.sequence(uniquePhones map (p => persist.User.find(p.userId).headOption map (_.map((_, p.number))))) map (_.flatten) // TODO: #perf lots of sql queries
            userStructsSalts <- DBIO.sequence(usersPhones.map {
              case (u, phoneNumber) =>
                UserUtils.userStruct(u, phonesMap(phoneNumber), client.authId) map (us => (us, u.accessSalt))
            })
          } yield {
              val userPhoneNumbers = userPhones.map(_.number).toSet

              userStructsSalts.foldLeft((immutable.Seq.empty[(User, String)], immutable.Set.empty[Int], userPhoneNumbers)) {
                case ((userStructSalts, newContactIds, _), userStructSalt) =>
                  (
                    userStructSalts :+ userStructSalt,
                    newContactIds + userStructSalt._1.id,
                    userPhoneNumbers
                    )
              }
            }

          f flatMap {
            case (userStructsSalts, newContactIds, registeredPhoneNumbers) =>
              actorSystem.log.debug("Phone numbers: {}, registered: {}", phoneNumbers, registeredPhoneNumbers)

              (phoneNumbers &~ registeredPhoneNumbers).foreach { phoneNumber =>
                actorSystem.log.debug("Inserting UnregisteredContact {} {}", phoneNumber, client.userId)

                persist.contact.UnregisteredContact.createIfNotExists(phoneNumber, client.userId)
              }

              if (userStructsSalts.nonEmpty) {
                newContactIds foreach { id =>
                  // TODO: record social relation
                }

                for {
                  _ <- createAllUserContacts(client.userId, userStructsSalts)
                  seqstate <- broadcastClientUpdate(seqUpdManagerRegion, UpdateContactsAdded(newContactIds.toVector))
                } yield {
                  Ok(ResponseImportContacts(userStructsSalts.toVector.map(_._1), seqstate._1, seqstate._2))
                }
              } else {
                DBIO.successful(Ok(ResponseImportContacts(immutable.Vector.empty, 0, Array.empty)))
              }
          }
        }
      }
    }

    db.run(toDBIOAction(action)) // TODO: transactionally
  }

  override def jhandleGetContacts(contactsHash: String, clientData: ClientData): Future[HandlerResult[ResponseGetContacts]] = {
    val authorizedAction = requireAuth(clientData).map { implicit client =>
      val action = persist.contact.UserContact.findContactIdsAll(client.userId).map(hashIds).flatMap { hash =>
        if (contactsHash == hash) {
          DBIO.successful(Ok(ResponseGetContacts(Vector.empty[users.User], isNotChanged = true)))
        } else {
          for {
            userIdsNames <- persist.contact.UserContact.findContactIdsWithLocalNames(client.userId)
            userIds = userIdsNames.map(_._1).toSet
            users <- persist.User.findByIds(userIds)
            namesMap = immutable.Map(userIdsNames: _*)
            // TODO: #perf optimize (so much requests!)
            userStructs <- DBIO.sequence(users.map(user =>
              util.UserUtils.userStruct(user, namesMap.get(user.id).getOrElse(None), clientData.authId)))
          } yield {
            Ok(ResponseGetContacts(
              users = userStructs.toVector,
              isNotChanged = false
            ))
          }
        }
      }

      action.transactionally
    }

    db.run(toDBIOAction(authorizedAction))
  }

  override def jhandleRemoveContact(userId: Int, accessHash: Long, clientData: ClientData): Future[HandlerResult[ResponseSeq]] = {
    val authorizedAction = requireAuth(clientData).map { implicit client =>
      persist.contact.UserContact.find(ownerUserId = client.userId, contactUserId = userId).flatMap {
        case Some(contact) =>
          if (accessHash == util.ACL.userAccessHash(clientData.authId, userId, contact.accessSalt)) {
            for {
              _ <- persist.contact.UserContact.delete(client.userId, userId)
              _ <- broadcastClientUpdate(seqUpdManagerRegion, UpdateUserLocalNameChanged(userId, None))
              seqstate <- broadcastClientUpdate(seqUpdManagerRegion, UpdateContactsRemoved(Vector(userId)))
            } yield {
              Ok(ResponseSeq(seqstate._1, seqstate._2))
            }
          } else {
            DBIO.successful(Error(CommonErrors.InvalidAccessHash))
          }
        case None => DBIO.successful(Error(Errors.ContactNotFound))
      }
    }

    db.run(toDBIOAction(authorizedAction))
  }

  override def jhandleAddContact(userId: Int, accessHash: Long, clientData: ClientData): Future[HandlerResult[ResponseSeq]] = {
    val authorizedAction = requireAuth(clientData).map { implicit client =>
      val action = (for {
        optUser <- persist.User.find(userId).headOption
        optNumber <- optUser.map(user => persist.UserPhone.findByUserId(user.id).headOption).getOrElse(DBIO.successful(None))
      } yield {
          (optUser, optNumber map (_.number))
        }).flatMap {
        case (Some(user), Some(userPhoneNumber)) =>
          if (accessHash == util.ACL.userAccessHash(clientData.authId, user.id, user.accessSalt)) {
            persist.contact.UserContact.find(ownerUserId = client.userId, contactUserId = userId).flatMap {
              case None =>
                addContactSendUpdate(client.userId, user.id, userPhoneNumber, None, user.accessSalt) map {
                  case (seq, state) => Ok(ResponseSeq(seq, state))
                }
              case Some(contact) =>
                DBIO.successful(Error(Errors.ContactAlreadyExists))
            }
          } else DBIO.successful(Error(CommonErrors.InvalidAccessHash))
        case (None, _) => DBIO.successful(Error(CommonErrors.UserNotFound))
        case (_, None) => DBIO.successful(Error(CommonErrors.UserPhoneNotFound))
      }

      action.transactionally
    }

    db.run(toDBIOAction(authorizedAction))
  }

  override def jhandleSearchContacts(request: String, clientData: ClientData): Future[HandlerResult[ResponseSearchContacts]] = throw new NotImplementedError()

  private def addContactSendUpdate(clientUserId: Int,
                                   userId: Int,
                                   phoneNumber: Long,
                                   name: Option[String],
                                   accessSalt: String
                                    )(
                                    implicit client: AuthorizedClientData
                                    ) = {
    for {
      _ <- persist.contact.UserContact.createOrRestore(clientUserId, userId, phoneNumber, name, accessSalt)
      seqstate <- broadcastClientUpdate(seqUpdManagerRegion, UpdateContactsAdded(Vector(userId)))
    } yield seqstate
  }

  private def createAllUserContacts(ownerUserId: Int, contacts: immutable.Seq[(User, String)]) = {
    persist.contact.UserContact.findIds(ownerUserId, contacts.map(_._1.id).toSet).flatMap { existingContactUserIds =>
      val actions = contacts map {
        case (userStruct, accessSalt) =>
          val userContact = models.contact.UserContact(
            ownerUserId = ownerUserId,
            contactUserId = userStruct.id,
            phoneNumber = userStruct.phone,
            name = userStruct.localName,
            accessSalt = accessSalt,
            isDeleted = false
          )

          val action = if (existingContactUserIds.contains(userStruct.id)) {
            persist.contact.UserContact.insertOrUpdate(userContact)
          } else {
            persist.contact.UserContact.createOrRestore(
              ownerUserId = userContact.ownerUserId,
              contactUserId = userContact.contactUserId,
              phoneNumber = userContact.phoneNumber,
              name = userContact.name,
              accessSalt = userContact.accessSalt
            )
          }

          action map (_ => userContact)
      }

      DBIO.sequence(actions)
    }
  }
}
