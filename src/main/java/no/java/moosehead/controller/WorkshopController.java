package no.java.moosehead.controller;

import no.java.moosehead.MoosheadException;
import no.java.moosehead.api.*;
import no.java.moosehead.commands.AddReservationCommand;
import no.java.moosehead.eventstore.ReservationAddedByUser;
import no.java.moosehead.projections.Workshop;
import no.java.moosehead.repository.WorkshopData;
import no.java.moosehead.web.Configuration;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class WorkshopController implements ParticipantApi {
    @Override
    public List<WorkshopInfo> workshops() {
        List<Workshop> workshops = SystemSetup.instance().workshopListProjection().getWorkshops();
        return workshops.stream()
                .map(ws -> {
                    WorkshopData wd = ws.getWorkshopData();
                    WorkshopStatus status = computeWorkshopStatus(ws);

                    return new WorkshopInfo(wd.getId(),wd.getTitle(),wd.getDescription(), status);
                })
                .collect(Collectors.toList())
        ;
    }

    private WorkshopStatus computeWorkshopStatus(Workshop ws) {
        if (Configuration.openTime().isAfter(OffsetDateTime.now())) {
            return WorkshopStatus.NOT_OPENED;
        }
        int seatsLeft = ws.getNumberOfSeats() - ws.getParticipants().size();
        if (seatsLeft <= 0) {
            return WorkshopStatus.FULL;
        }
        if (seatsLeft < 5) {
            return WorkshopStatus.FEW_SPOTS;
        }
        return WorkshopStatus.FREE_SPOTS;
    }

    @Override
    public ParticipantActionResult reservation(String workshopid, String email, String fullname) {
        AddReservationCommand arc = new AddReservationCommand(email,fullname,workshopid);
        ReservationAddedByUser event;

        try {
            event = SystemSetup.instance().workshopAggregate().createEvent(arc);
        } catch (MoosheadException e) {
            return ParticipantActionResult.error(e.getMessage());
        }
        SystemSetup.instance().eventstore().addEvent(event);
        return ParticipantActionResult.confirmEmail();
    }

    @Override
    public ParticipantActionResult confirmEmail(String token) {
        return ParticipantActionResult.ok();
    }

    @Override
    public ParticipantActionResult cancellation(String workshopid, String email) {
        return ParticipantActionResult.ok();
    }

    @Override
    public List<ParticipantReservation> myReservations(String email) {
        return new ArrayList<>();
    }
}