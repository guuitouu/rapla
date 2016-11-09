package org.rapla.plugin.eventtimecalculator;

import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.TableModel;

import org.rapla.components.tablesorter.TableSorter;
import org.rapla.entities.domain.AppointmentBlock;
import org.rapla.entities.domain.Reservation;
import org.rapla.framework.RaplaException;
import org.rapla.inject.Extension;
import org.rapla.inject.ExtensionRepeatable;
import org.rapla.plugin.tableview.client.swing.AppointmentTableModel;
import org.rapla.plugin.tableview.client.swing.ReservationTableModel;
import org.rapla.plugin.tableview.client.swing.extensionpoints.AppointmentSummaryExtension;
import org.rapla.plugin.tableview.client.swing.extensionpoints.ReservationSummaryExtension;


@ExtensionRepeatable({
    @Extension(provides = ReservationSummaryExtension.class, id = EventTimeCalculatorPlugin.PLUGIN_ID),
    @Extension(provides = AppointmentSummaryExtension.class, id = EventTimeCalculatorPlugin.PLUGIN_ID)
})
public final class DurationCounter  implements ReservationSummaryExtension, AppointmentSummaryExtension
{
    EventTimeCalculatorFactory factory;
    protected final EventTimeCalculatorResources i18n;


    @Inject
    public DurationCounter(EventTimeCalculatorFactory factory,EventTimeCalculatorResources i18n)  {
        this.i18n = i18n;
        this.factory = factory;
    }

    public void init(final JTable table, JPanel summaryRow) {
 		
    	final JLabel counter = new JLabel();
        summaryRow.add( Box.createHorizontalStrut(30));
        summaryRow.add( counter);
        table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {

             public void valueChanged(ListSelectionEvent arg0)
             {
            	 EventTimeModel eventTimeModel = factory.getEventTimeModel();
            	 int[] selectedRows = table.getSelectedRows();
                 TableModel model = table.getModel();
                 TableSorter sorterModel = null;
                 if ( model instanceof TableSorter)
                 {
                     sorterModel = ((TableSorter) model);
                     model = ((TableSorter)model).getTableModel();
                 }
                 long totalduration = 0;
                 for ( int row:selectedRows)
                 {
                     if (sorterModel != null)
                        row = sorterModel.modelIndex(row);
                     if ( model instanceof AppointmentTableModel)
                     {
                         AppointmentBlock block = ((AppointmentTableModel) model).getAppointmentAt(row);
                         long duration = eventTimeModel.calcDuration(block);
                         totalduration+= duration;
                     }
                     if ( model instanceof ReservationTableModel)
                     {
                         Reservation block = ((ReservationTableModel) model).getReservationAt(row);
                         long duration = eventTimeModel.calcDuration(block);
                         if ( duration <0)
                         {
                             totalduration = -1;
                             break;
                         }
                         totalduration+= duration;
                     }
                 }
                 String durationString = totalduration < 0 ? i18n.getString("infinite") : eventTimeModel.format(totalduration);
                 counter.setText( i18n.getString("total_duration") + " " + durationString);
             }
         });
     }
 }
