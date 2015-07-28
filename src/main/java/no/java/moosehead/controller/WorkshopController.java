package no.java.moosehead.controller;

import no.java.moosehead.MoosheadException;
import no.java.moosehead.aggregate.WorkshopAggregate;
import no.java.moosehead.aggregate.WorkshopNotFoundException;
import no.java.moosehead.api.*;
import no.java.moosehead.commands.*;
import no.java.moosehead.eventstore.AbstractReservationAdded;
import no.java.moosehead.eventstore.AbstractReservationCancelled;
import no.java.moosehead.eventstore.EmailConfirmedByUser;
import no.java.moosehead.eventstore.*;
import no.java.moosehead.projections.Participant;
import no.java.moosehead.projections.Workshop;
import no.java.moosehead.repository.WorkshopData;
import no.java.moosehead.repository.WorkshopRepository;
import no.java.moosehead.web.Configuration;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class WorkshopController implements ParticipantApi,AdminApi {
    @Override
    public WorkshopInfo getWorkshop(String workshopid) {
        List<Workshop> workshops = SystemSetup.instance().workshopListProjection().getWorkshops();
        Optional<Workshop> workshopOptional = workshops.stream()
                .filter(ws -> ws.getWorkshopData().getId().equals(workshopid))
                .findFirst();

        if (workshopOptional.isPresent()) {
            Workshop ws = workshopOptional.get();
            WorkshopData wd = ws.getWorkshopData();
            WorkshopStatus status = computeWorkshopStatus(ws);
            return new WorkshopInfo(wd.getId(), wd.getTitle(), wd.getDescription(), ws.getParticipants(), status);
        } else
            throw new WorkshopNotFoundException();
    }

    @Override
    public List<WorkshopInfo> workshops() {
        List<Workshop> workshops = SystemSetup.instance().workshopListProjection().getWorkshops();
        return workshops.stream()
                .map(ws -> {
                    WorkshopData wd = ws.getWorkshopData();
                    WorkshopStatus status = computeWorkshopStatus(ws);

                    return new WorkshopInfo(wd.getId(),wd.getTitle(),wd.getDescription(), ws.getParticipants(), status);
                })
                .collect(Collectors.toList())
        ;
    }

    protected WorkshopStatus computeWorkshopStatus(Workshop ws) {
        if (Configuration.closedWorkshops().contains(ws.getWorkshopData().getId()) ||
                (ws.getWorkshopData().hasStartAndEndTime() && ws.getWorkshopData().getStartTime().isBefore(Instant.now()))) {
            return WorkshopStatus.CLOSED;
        }
        if (Configuration.openTime().isAfter(OffsetDateTime.now())) {
            return WorkshopStatus.NOT_OPENED;
        }
        int confirmedParticipants = ws.getParticipants().stream()
                .filter(Participant::isEmailConfirmed)
                .mapToInt(Participant::getNumberOfSeatsReserved)
                .sum();
        int seatsLeft = ws.getNumberOfSeats() - confirmedParticipants;
        if (seatsLeft <= -Configuration.veryFullNumber()) {
            return WorkshopStatus.VERY_FULL;
        }
        if (seatsLeft <= 0) {
            return WorkshopStatus.FULL;
        }
        if (seatsLeft < 5) {
            return WorkshopStatus.FEW_SPOTS;
        }
        return WorkshopStatus.FREE_SPOTS;
    }

    @Override
    public ParticipantActionResult reservation(String workshopid, String email, String fullname, AuthorEnum authorEnum, Optional<String> googleEmail) {
        AddReservationCommand arc = new AddReservationCommand(email,fullname,workshopid, authorEnum, googleEmail, WorkshopTypeEnum.NORMAL_WORKSHOP,1);
        AbstractReservationAdded event;

        WorkshopAggregate workshopAggregate = SystemSetup.instance().workshopAggregate();
        synchronized (workshopAggregate) {
            try {
                event = workshopAggregate.createEvent(arc);
            } catch (MoosheadException e) {
                return ParticipantActionResult.error(e.getMessage());
            }
            SystemSetup.instance().eventstore().addEvent(event);
        }
        if (SystemSetup.instance().workshopListProjection().isEmailConfirmed(event.getEmail())) {
            return ParticipantActionResult.ok();
        }
        return ParticipantActionResult.confirmEmail();
    }

    @Override
    public ParticipantActionResult cancellation(String reservationId, AuthorEnum authorEnum) {

        Optional<Participant> optByReservationId = SystemSetup.instance().workshopListProjection().findByReservationToken(reservationId);

        if (!optByReservationId.isPresent()) {
            return ParticipantActionResult.error("Unknown token, reservation not found");
        }

        Participant participant = optByReservationId.get();
        CancelReservationCommand cancelReservationCommand = new CancelReservationCommand(participant.getEmail(), participant.getWorkshopId(), authorEnum);
        AbstractReservationCancelled event;
        WorkshopAggregate workshopAggregate = SystemSetup.instance().workshopAggregate();
        synchronized (workshopAggregate) {
            try {
                event = workshopAggregate.createEvent(cancelReservationCommand);
            } catch (MoosheadException e) {
                return ParticipantActionResult.error(e.getMessage());
            }
            SystemSetup.instance().eventstore().addEvent(event);
        }
        return ParticipantActionResult.ok();
    }

    @Override
    public ParticipantActionResult confirmEmail(String token) {

        ConfirmEmailCommand confirmEmailCommand = new ConfirmEmailCommand(token);
        EmailConfirmedByUser emailConfirmedByUser;
        WorkshopAggregate workshopAggregate = SystemSetup.instance().workshopAggregate();
        synchronized (workshopAggregate) {
            try {
                emailConfirmedByUser = workshopAggregate.createEvent(confirmEmailCommand);
            } catch (MoosheadException e) {
                return ParticipantActionResult.error(e.getMessage());
            }
            SystemSetup.instance().eventstore().addEvent(emailConfirmedByUser);
        }
        return ParticipantActionResult.ok();
    }



    @Override
    public List<ParticipantReservation> myReservations(String email) {
        List<Participant> allReservations = SystemSetup.instance().workshopListProjection().findAllReservations(email);
        WorkshopRepository workshopRepository = SystemSetup.instance().workshopRepository();
        return allReservations.stream()
                .map(pa -> {
                    Optional<WorkshopData> workshopDataOptional = workshopRepository.workshopById(pa.getWorkshopId());
                    String name = workshopDataOptional.map(wd -> wd.getTitle()).orElse("xxx");
                    ParticipantReservationStatus status = !pa.isEmailConfirmed() ?
                            ParticipantReservationStatus.NOT_CONFIRMED :
                            pa.waitingListNumber() <= 0 ? ParticipantReservationStatus.HAS_SPACE : ParticipantReservationStatus.WAITING_LIST;
                    return new ParticipantReservation(pa.getEmail(), pa.getWorkshopId(), name, status, pa.getNumberOfSeatsReserved());
                })
                .collect(Collectors.toList());
    }

    @Override
    public ParticipantActionResult createWorkshop(WorkshopData workshopData, Instant startTime, Instant endTime, Instant openTime, int maxParticipants) {
        AddWorkshopCommand addWorkshopCommand = AddWorkshopCommand.builder()
                .withWorkshopId(workshopData.getId())
                .withWorkshopData(Optional.of(workshopData))
                .withStartTime(startTime)
                .withEndTime(endTime)
                .withNumberOfSeats(maxParticipants)
                .withAuthor(AuthorEnum.ADMIN)
                .create();
        WorkshopAggregate workshopAggregate = SystemSetup.instance().workshopAggregate();
        synchronized (workshopAggregate) {
            WorkshopAddedEvent event;
            try {
                event = workshopAggregate.createEvent(addWorkshopCommand);
            } catch (MoosheadException e) {
                return ParticipantActionResult.error(e.getMessage());
            }
            SystemSetup.instance().eventstore().addEvent(event);
        }
        return ParticipantActionResult.ok();
    }
}
