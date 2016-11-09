package org.rapla.client.extensionpoints;

import org.rapla.client.ReservationEdit;
import org.rapla.client.RaplaWidget;
import org.rapla.framework.RaplaException;
import org.rapla.inject.ExtensionPoint;
import org.rapla.inject.InjectionContext;

/** add a footer for summary of appointments in edit window
 * provide an AppointmentStatusFactory to add your own footer to the appointment edit
 @see AppointmentStatusFactory
  * */
@ExtensionPoint(context = InjectionContext.swing,id = "appointmentstatus")
public interface AppointmentStatusFactory {
	RaplaWidget createStatus(ReservationEdit reservationEdit) throws RaplaException;
}
